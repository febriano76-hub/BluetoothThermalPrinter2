# BluetoothThermalPrinter2 — BT Printer + Print Service (Android)

Aplikasi Android yang **mendaftarkan printer thermal Bluetooth ke sistem
Android sebagai Print Service**, sehingga printer bisa dipakai dari:
- Browser (Chrome, Firefox) lewat `window.print()`
- Aplikasi POS web yang pakai dialog cetak browser
- WhatsApp, Galeri, dan aplikasi apa pun yang punya tombol Print

Plus, masih ada mode cetak manual (ketik teks → cetak langsung).

> Catatan: ini repo `BluetoothThermalPrinter2` — repo baru karena yang lama
> rusak struktur folder-nya. Package name di Android tetap `com.example.btprinter`,
> jadi APK dari repo ini akan **menimpa** APK lama di HP (bukan jadi 2 app terpisah).

---

## 🚀 Build APK lewat GitHub Actions

### Cara A: Lewat GitHub Desktop (paling mudah)

1. Di GitHub Desktop, **File → Clone repository**
2. Pilih repo `BluetoothThermalPrinter2` di tab GitHub.com
3. Pilih lokasi penyimpanan lokal, klik **Clone**
4. **Repository → Show in Explorer** → buka folder repo lokal
5. **Pastikan folder repo lokal kosong** (cuma ada folder `.git` yang transparan)
6. Extract ZIP ini → **masuk ke dalam** folder `BluetoothThermalPrinter2/`
7. **Ctrl+A** pilih semua isinya, **Ctrl+C** copy
8. Paste ke folder repo lokal (Ctrl+V)
9. Struktur akhir folder repo lokal harus seperti ini:
   ```
   BluetoothThermalPrinter2/        ← folder repo lokal
   ├── .git/                        ← (transparan, bawaan, jangan diutak-atik)
   ├── .github/
   ├── app/
   ├── .gitignore
   ├── README.md
   ├── build.gradle.kts
   ├── gradle.properties
   └── settings.gradle.kts
   ```
10. Balik ke GitHub Desktop → akan terlihat banyak file changed
11. Tulis summary `Initial commit`, klik **Commit to main**, lalu **Push origin**

### Cara B: Drag-and-drop di web

1. Di halaman repo baru kosong, klik link **"uploading an existing file"**
2. Extract ZIP, masuk ke folder `BluetoothThermalPrinter2/`
3. **Ctrl+A** pilih semua isinya, **drag** ke browser
4. Tunggu nama file muncul di list
5. Scroll bawah, klik **Commit changes**

### ⚠️ Kesalahan umum yang harus dihindari

- ❌ Upload file ZIP itu sendiri (GitHub tidak auto-extract)
- ❌ Upload folder `BluetoothThermalPrinter2` itu sendiri sebagai folder (akan nested)
- ❌ Klik "choose your files" — cuma bisa pilih file individual, tidak bisa folder
- ✅ Buka folder, **Ctrl+A** isinya, drag/copy yang terpilih

### Setelah upload selesai

1. Klik tab **Actions** di repo Anda
2. Workflow "Build APK" otomatis mulai jalan (titik kuning berputar)
3. Tunggu ~5-10 menit sampai centang hijau ✅
4. Klik workflow → scroll ke bawah → klik **app-debug-apk** di Artifacts
5. Download ZIP, extract → file **app-debug.apk** siap di-install

---

## 📱 Setup setelah install APK

### Langkah 1: Pair & pilih printer di aplikasi

1. Pair printer thermal di **Setelan Android → Bluetooth** (kalau belum)
2. Buka aplikasi **BT Printer**
3. Pilih lebar kertas (58 mm atau 80 mm) di radio button atas
4. Pilih printer dari dropdown
5. Tap **Hubungkan** — status harus jadi "Terhubung ke ..."
6. (Opsional) Tap **Tes Cetak** untuk verifikasi printer jalan

Setelah connect sukses, info printer tersimpan otomatis di banner biru
"Print Service Android" di atas.

### Langkah 2: Aktifkan Print Service

1. Tap tombol **"Buka Setelan Print Service Android"**
   (atau manual: Setelan Android → Setelan lainnya → **Pencetakan / Layanan Pencetakan**)
2. Cari **BT Printer** di daftar layanan
3. Toggle **On**
4. Setelan akan minta konfirmasi izin → tap **OK / Izinkan**

### Langkah 3: Test dari browser

1. Buka POS Anda di Chrome
2. Tampilkan halaman struk
3. Tap tombol **🖨 Cetak Struk** di POS (atau **Ctrl+P** / menu Print browser)
4. Di dialog cetak, di bagian **Tujuan / Destination**, pilih **BT Printer**
5. Tap **Print**
6. Struk akan langsung dicetak ke printer thermal Anda

---

## 🔧 Cara kerja teknisnya

```
[Browser/POS]   →   [Android Print Framework]   →   [BT Printer Service]
   tap Print       memilih printer dari daftar      menerima PDF, render
                                                    ke bitmap, convert ke
                                                    ESC/POS, kirim via BT
                                                              ↓
                                                    [Printer Thermal]
```

- POS render struk → browser kirim sebagai **PDF**
- Print Service kita terima PDF, render tiap halaman ke **bitmap** pakai `PdfRenderer`
- Bitmap di-scale ke 384 px (58mm) atau 576 px (80mm)
- Bitmap dikonversi ke **monokrom 1-bit** (threshold luminance 160)
- Dibagi jadi strip 128 px supaya tidak overflow buffer printer
- Tiap strip dikirim sebagai perintah ESC/POS raster (`GS v 0`)

---

## 🛠️ Troubleshooting

| Masalah | Solusi |
|---|---|
| BT Printer tidak muncul di Setelan Pencetakan | Buka aplikasi BT Printer dulu sekali, atau restart HP |
| BT Printer tidak muncul di dialog cetak browser | Pastikan toggle Print Service-nya **On**, dan tutup-buka tab browser |
| Print error "Pilih printer dulu" | Buka app BT Printer → Hubungkan ke printer dulu |
| Print job stuck di antrian | Buka panel notifikasi → tap notifikasi print → **Cancel** → coba lagi |
| Hasil cetak terlalu pudar/hitam | Edit `ImageToEscPos.kt` → ubah `threshold = 160` (turunkan = lebih hitam) |
| Cetak terpotong di kanan | Cek lebar kertas di app (58 vs 80 mm) sesuai printer Anda |
| Cetak sangat lambat | Halaman PDF besar = banyak data. Coba print 1 halaman dulu, atau kecilkan dokumen |
| Print job otomatis fail | Cek Bluetooth nyala, printer ON, jarak dekat. Lihat Logcat untuk detail |

---

## 📝 Catatan v1 Print Service

Print Service di Android itu kompleks. **Versi ini adalah v1** — fungsional
tapi belum dipoles seperti aplikasi profesional. Kalau ada bug, kirim
screenshot/deskripsi ke chat — kita fine-tune.

---

## 🔄 Mode Cetak Manual (Tetap Tersedia)

Selain Print Service, mode cetak manual lama tetap jalan:
- Ketik teks di EditText → tap **CETAK**
- Atau tap **Tes Cetak** untuk struk template

---

## 📄 Lisensi
Bebas dipakai, dimodifikasi, dan didistribusikan ulang.
