package com.example.btprinter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var prefs: PrinterPrefs

    private lateinit var spinnerDevices: Spinner
    private lateinit var btnRefresh: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnPrint: Button
    private lateinit var btnTest: Button
    private lateinit var btnPrintSettings: Button
    private lateinit var editText: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvSavedPrinter: TextView
    private lateinit var radioPaperWidth: RadioGroup

    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var selectedDevice: BluetoothDevice? = null

    // ===== Activity result launchers =====

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            ensureBluetoothOn()
        } else {
            toast("Izin Bluetooth diperlukan agar aplikasi bisa berjalan")
        }
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            loadPairedDevices()
        } else {
            toast("Bluetooth harus dinyalakan")
        }
    }

    // ===== Lifecycle =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrinterPrefs(this)

        spinnerDevices = findViewById(R.id.spinnerDevices)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnPrint = findViewById(R.id.btnPrint)
        btnTest = findViewById(R.id.btnTest)
        btnPrintSettings = findViewById(R.id.btnPrintSettings)
        editText = findViewById(R.id.editText)
        tvStatus = findViewById(R.id.tvStatus)
        tvSavedPrinter = findViewById(R.id.tvSavedPrinter)
        radioPaperWidth = findViewById(R.id.radioPaperWidth)

        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
        if (bluetoothAdapter == null) {
            updateStatus("Perangkat ini tidak mendukung Bluetooth")
            disableAll()
            return
        }

        // Set paper width radio dari saved prefs
        radioPaperWidth.check(
            if (prefs.paperWidthMm == 80) R.id.rb80mm else R.id.rb58mm
        )
        radioPaperWidth.setOnCheckedChangeListener { _, checkedId ->
            prefs.paperWidthMm = if (checkedId == R.id.rb80mm) 80 else 58
            updateSavedPrinterInfo()
        }

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedDevice = pairedDevices.getOrNull(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {
                selectedDevice = null
            }
        }

        btnRefresh.setOnClickListener { checkPermissionsThenInit() }
        btnConnect.setOnClickListener { connect() }
        btnDisconnect.setOnClickListener {
            BluetoothPrinter.disconnect()
            updateStatus("Terputus")
        }
        btnPrint.setOnClickListener { printUserText() }
        btnTest.setOnClickListener { printTestReceipt() }
        btnPrintSettings.setOnClickListener { openPrintServiceSettings() }

        updateSavedPrinterInfo()
        checkPermissionsThenInit()
    }

    override fun onResume() {
        super.onResume()
        updateSavedPrinterInfo()  // refresh kalau user balik dari settings
    }

    override fun onDestroy() {
        BluetoothPrinter.disconnect()
        super.onDestroy()
    }

    // ===== Permissions & Bluetooth init =====

    private fun checkPermissionsThenInit() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            ensureBluetoothOn()
        } else {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun ensureBluetoothOn() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            loadPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        try {
            pairedDevices = adapter.bondedDevices.toList()
            val names = pairedDevices.map { d ->
                val name = try { d.name ?: "(tanpa nama)" } catch (_: SecurityException) { "(tanpa nama)" }
                "$name — ${d.address}"
            }
            val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
            arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerDevices.adapter = arrayAdapter

            // Auto-select printer terakhir yang dipakai
            val savedMac = prefs.lastPrinterMac
            if (savedMac != null) {
                val idx = pairedDevices.indexOfFirst { it.address == savedMac }
                if (idx >= 0) spinnerDevices.setSelection(idx)
            }

            if (pairedDevices.isEmpty()) {
                updateStatus("Tidak ada printer ter-pair")
                toast("Pair printer dulu di Pengaturan Bluetooth Android")
            } else {
                updateStatus("Ditemukan ${pairedDevices.size} perangkat. Pilih lalu Hubungkan.")
            }
        } catch (e: SecurityException) {
            updateStatus("Izin Bluetooth ditolak")
        }
    }

    // ===== Print actions =====

    private fun connect() {
        val device = selectedDevice
        if (device == null) {
            toast("Pilih printer dulu")
            return
        }
        updateStatus("Menghubungkan...")
        btnConnect.isEnabled = false
        lifecycleScope.launch {
            val result = BluetoothPrinter.connect(device)
            btnConnect.isEnabled = true
            result.fold(
                onSuccess = {
                    @SuppressLint("MissingPermission")
                    val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }

                    // Simpan MAC + name supaya PrintService bisa pakai
                    prefs.lastPrinterMac = device.address
                    prefs.lastPrinterName = name
                    updateSavedPrinterInfo()
                    updateStatus("Terhubung ke $name")
                },
                onFailure = { e ->
                    updateStatus("Gagal terhubung: ${e.message}")
                }
            )
        }
    }

    private fun printUserText() {
        val text = editText.text.toString()
        if (text.isBlank()) {
            toast("Isi teks dulu")
            return
        }
        if (!BluetoothPrinter.isConnected) {
            toast("Belum terhubung ke printer")
            return
        }
        lifecycleScope.launch {
            val result = BluetoothPrinter.printText(text)
            result.fold(
                onSuccess = { toast("Terkirim ke printer") },
                onFailure = { e -> toast("Gagal cetak: ${e.message}") }
            )
        }
    }

    private fun printTestReceipt() {
        if (!BluetoothPrinter.isConnected) {
            toast("Belum terhubung ke printer")
            return
        }
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val width = if (prefs.paperWidthMm == 80) 48 else 32
        val text = buildString {
            appendLine("=".repeat(width))
            appendLine("TES CETAK BERHASIL".padStart((width + 18) / 2).padEnd(width))
            appendLine("=".repeat(width))
            appendLine("Tanggal : $now")
            appendLine("Lebar   : ${prefs.paperWidthMm}mm")
            appendLine("-".repeat(width))
            appendLine("Printer thermal Bluetooth")
            appendLine("siap digunakan.")
            appendLine("-".repeat(width))
            appendLine("Terima kasih!")
        }
        lifecycleScope.launch {
            val result = BluetoothPrinter.printText(text)
            result.fold(
                onSuccess = { toast("Tes cetak terkirim") },
                onFailure = { e -> toast("Gagal: ${e.message}") }
            )
        }
    }

    private fun openPrintServiceSettings() {
        // Buka pengaturan Print Service Android (System Settings)
        try {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        } catch (e: Exception) {
            // Fallback ke Settings umum
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                toast("Cari 'Pencetakan' atau 'Printing' di pengaturan")
            } catch (_: Exception) {
                toast("Tidak bisa buka Setelan")
            }
        }
    }

    // ===== Helpers =====

    private fun updateSavedPrinterInfo() {
        val mac = prefs.lastPrinterMac
        val name = prefs.lastPrinterName
        val width = prefs.paperWidthMm
        tvSavedPrinter.text = if (mac == null) {
            "Belum ada printer tersimpan untuk Print Service"
        } else {
            "Print Service: $name (${width}mm)"
        }
    }

    private fun disableAll() {
        btnRefresh.isEnabled = false
        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = false
        btnPrint.isEnabled = false
        btnTest.isEnabled = false
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = "Status: $msg"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
