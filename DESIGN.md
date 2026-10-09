# DESIGN.md — Toko Simulasi (Android, Jetpack Compose)

Dokumen ini adalah **sumber kebenaran tunggal** untuk tampilan dan rasa aplikasi. Kalau ada konflik dengan `docs/uiux.md` atau default Material 3, dokumen ini yang menang.

Aplikasi: e-commerce **simulasi** — pengguna menjelajah katalog, mengisi keranjang, checkout dengan saldo virtual, dan menerima struk. Tidak ada uang sungguhan yang berpindah.

---

## 1. Arah Visual: "Struk & Rak Toko"

Referensinya bukan Shopee/Tokopedia, bukan juga landing page startup. Referensinya adalah **benda fisik belanja**: struk kasir thermal, label harga di rak, kantong kertas cokelat, cap stempel "LUNAS".

Artinya:
- **Latar kertas hangat**, bukan putih murni dan bukan abu-abu dingin.
- **Tinta hitam pekat** untuk teks, garis tipis 1dp sebagai pemisah — bukan bayangan.
- **Satu warna aksen** (merah cabai) dipakai hemat: tombol utama, harga diskon, badge keranjang. Itu saja.
- **Angka pakai font monospace** — harga, kuantitas, nomor pesanan, saldo. Seperti struk.
- Sudut **kecil** (4–8dp). Aplikasi ini terasa seperti kertas dan label, bukan seperti permen.

Kalimat uji: *kalau sebuah layar di-screenshot lalu dicetak hitam-putih, apakah masih terbaca dan masih terasa seperti toko?* Kalau ya, desainnya benar.

---

## 2. Daftar Larangan (Anti-AI-Slop)

Hal-hal berikut **dilarang** karena membuat aplikasi terlihat generik/hasil generator:

| Jangan | Gantinya |
|---|---|
| Ungu default Material (`#6750A4`) dan dynamic color Material You | Palet tetap di §3, `dynamicColor = false` |
| Gradien ungu–biru, gradien di tombol, gradien di latar | Warna solid |
| Glassmorphism, blur, kartu transparan | Permukaan solid + border 1dp |
| Semua kartu `RoundedCornerShape(16–24.dp)` + shadow lembut | Radius 4/8dp, border `outline`, elevation 0 |
| Emoji sebagai ikon atau dekorasi (🛒🔥✨) | Ikon garis (Material Symbols Outlined / Lucide), stroke konsisten |
| Ilustrasi 3D blob / orang tanpa wajah di empty state | Tipografi besar + satu ikon garis + kalimat jelas |
| Copy kosong: "Discover amazing products!", "Unlock your potential" | Copy konkret berbahasa Indonesia (lihat §9) |
| Data dummy "Product 1", "Lorem ipsum", harga `$99.99` | Produk lokal yang masuk akal, format `Rp 24.500` |
| Banner carousel auto-slide di beranda | Satu blok promo statis yang bisa ditutup |
| Shimmer di semua tempat | Skeleton hanya di grid produk; sisanya teks "Memuat…" |
| Badge warna-warni di setiap kartu ("HOT", "NEW", "BEST") | Maksimal satu label per kartu, hanya jika benar |
| Bottom nav dengan 5+ ikon | 4 tab |
| Angka harga pakai font proporsional yang "menari" saat berubah | Monospace + `tabular` |

---

## 3. Warna

### 3.1 Token

| Token | Terang | Gelap | Pemakaian |
|---|---|---|---|
| `paper` (background) | `#F4F1EA` | `#151513` | Latar layar |
| `surface` | `#FBFAF6` | `#1D1D1A` | Kartu, sheet, input |
| `surfaceSunken` | `#EAE6DC` | `#0F0F0E` | Area gambar produk, field nonaktif |
| `ink` (onBackground) | `#1A1A17` | `#ECE9E1` | Teks utama, ikon |
| `inkMuted` | `#5E5B53` | `#A19D93` | Teks sekunder, label |
| `inkFaint` | `#8F8B80` | `#6D6A62` | Placeholder, harga coret |
| `line` (outline) | `#D6D1C4` | `#34332F` | Border, divider |
| `cabai` (primary) | `#C8361A` | `#F0603F` | CTA utama, harga diskon, badge |
| `onCabai` | `#FFFFFF` | `#1A0A05` | Teks di atas cabai |
| `daun` (success) | `#2E5E3A` | `#7FBF8E` | Stok tersedia, pembayaran berhasil, cap LUNAS |
| `kunyit` (warning) | `#B7800F` | `#E6B44A` | Stok menipis, saldo hampir habis |
| `tinta-biru` (info/link) | `#24497A` | `#8AAEE0` | Tautan teks, status "Dikirim" |

### 3.2 Aturan
- Rasio kasar per layar: **80% paper/surface, 15% ink, ≤5% cabai.** Kalau cabai terasa di mana-mana, ada yang salah.
- Hanya **satu** tombol berwarna cabai per layar.
- Status pesanan pakai warna teks + titik 8dp, **bukan** chip berlatar penuh.
- Kontras teks minimal 4.5:1 (semua pasangan di atas sudah lolos untuk `ink`/`inkMuted` di atas `paper`/`surface`).

---

## 4. Tipografi

Dua keluarga, dibundel di `res/font/` (jangan pakai downloadable fonts supaya offline aman):

- **UI & judul:** `Instrument Sans` (Regular 400, Medium 500, SemiBold 600)
- **Angka:** `JetBrains Mono` (Regular 400, Medium 500) — harga, qty, saldo, nomor pesanan, tanggal di struk

| Token | Font | Ukuran / Line height | Berat | Contoh pemakaian |
|---|---|---|---|---|
| `display` | Instrument Sans | 32 / 36 sp | 600 | Total di layar sukses, saldo di Profil |
| `headline` | Instrument Sans | 24 / 30 sp | 600 | Judul layar |
| `title` | Instrument Sans | 18 / 24 sp | 600 | Nama produk di detail, judul section |
| `body` | Instrument Sans | 15 / 22 sp | 400 | Deskripsi, teks umum |
| `bodyStrong` | Instrument Sans | 15 / 22 sp | 500 | Nama produk di kartu |
| `label` | Instrument Sans | 13 / 18 sp | 500 | Tombol kecil, tab, chip |
| `caption` | Instrument Sans | 12 / 16 sp | 400 | Metadata, berat produk, toko |
| `overline` | Instrument Sans | 11 / 14 sp, letterSpacing 0.08em, UPPERCASE | 600 | Header section ("RINGKASAN", "ALAMAT") |
| `price` | JetBrains Mono | 16 / 20 sp | 500 | Harga di kartu |
| `priceLarge` | JetBrains Mono | 24 / 28 sp | 500 | Harga di detail produk |
| `mono` | JetBrains Mono | 13 / 18 sp | 400 | Nomor pesanan, baris struk |

Aturan:
- Judul **rata kiri**, tidak pernah center (kecuali layar sukses).
- Tidak ada teks bergradien, tidak ada teks berbayang.
- Maksimal 3 ukuran font berbeda per layar.

---

## 5. Spasi, Grid, Bentuk

- **Grid dasar 4dp.** Nilai yang boleh: `4, 8, 12, 16, 20, 24, 32, 48`.
- Margin samping layar: **16dp**.
- Jarak antar section: **24dp**. Jarak di dalam kartu: **12dp**.
- Grid produk: 2 kolom, gutter 12dp. Rasio gambar **4:5** (bukan 1:1 — lebih mirip kemasan di rak).

| Token radius | Nilai | Pemakaian |
|---|---|---|
| `none` | 0dp | Struk, divider, gambar di dalam kartu |
| `sm` | 4dp | Chip, badge, input, gambar thumbnail |
| `md` | 8dp | Kartu, tombol, bottom sheet (atas saja) |
| `full` | 50% | Hanya titik status & badge angka keranjang |

**Elevation:** semua 0dp. Pemisahan lewat `line` 1dp. Satu-satunya pengecualian: bottom bar checkout yang menempel di bawah boleh punya border atas 1dp — tetap tanpa shadow.

---

## 6. Ikon & Gambar

- Ikon: **Material Symbols Outlined**, weight 300, ukuran 20dp (dalam teks) / 24dp (navigasi). Jangan campur filled dan outlined, kecuali tab aktif di bottom nav (filled).
- Foto produk: latar polos (`surfaceSunken`), objek di tengah, tanpa border radius besar. Untuk dummy, pakai foto produk nyata yang konsisten pencahayaannya, atau **placeholder tipografis** (inisial produk + kategori dalam `overline`) — jangan pakai gambar AI acak.
- Tidak ada ilustrasi maskot.

---

## 7. Komponen

### 7.1 Tombol
| Varian | Tampilan | Pakai untuk |
|---|---|---|
| `Primary` | Latar `cabai`, teks `onCabai`, radius 8, tinggi 48dp, label 15sp/600 | Satu aksi utama: "Tambah ke keranjang", "Bayar sekarang" |
| `Secondary` | Latar transparan, border 1dp `ink`, teks `ink` | "Beli langsung", "Lihat pesanan" |
| `Text` | Teks `ink` bergaris bawah | "Hapus", "Ubah alamat" |

Tombol full-width hanya di bottom bar. State pressed: latar menggelap 8%, **tanpa** ripple berwarna.

### 7.2 Kartu Produk (grid)
```
┌────────────────────┐
│                    │  ← gambar 4:5, latar surfaceSunken
│      [gambar]      │
│                    │
├────────────────────┤
│ Kopi Gayo Arabika  │  bodyStrong, maks 2 baris
│ 250 g · Takengon   │  caption, inkMuted
│ Rp 68.000          │  price (mono)
│ Rp 85.000  -20%    │  caption coret inkFaint + label cabai (opsional)
└────────────────────┘
```
- Border 1dp `line`, radius 8, tanpa shadow.
- Tombol "+" kecil (32dp, border `ink`) di pojok kanan bawah untuk tambah cepat; berubah jadi stepper `− 1 +` setelah ditekan.
- Stok ≤ 5: baris tambahan `Sisa 3` warna `kunyit`.

### 7.3 Harga
- Selalu format `Rp 24.500` (spasi setelah Rp, titik ribuan, tanpa desimal). Buat satu fungsi `formatRupiah(Long)` — harga disimpan sebagai `Long` rupiah, **bukan** `Double`.
- Harga coret di **bawah** harga aktif, bukan di sebelahnya.

### 7.4 Stepper Kuantitas
`[ − ]  2  [ + ]` — tombol kotak 32dp border 1dp, angka mono di tengah lebar tetap 32dp supaya tidak bergeser dari 9 ke 10.

### 7.5 Chip Kategori
Teks `label` dengan border 1dp `line`, radius 4. Terpilih: latar `ink`, teks `paper`. Scroll horizontal, tanpa ikon.

### 7.6 Input
Latar `surface`, border 1dp `line` → `ink` saat fokus (bukan warna aksen). Label di atas field (bukan floating). Pesan error di bawah, warna `cabai`, kalimat lengkap.

### 7.7 Bottom Navigation (4 tab)
`Beranda · Kategori · Keranjang · Akun`
Latar `paper`, border atas 1dp. Tab aktif: ikon filled + label `ink`; tidak aktif: outlined + `inkMuted`. **Tanpa** pill indikator Material 3. Badge keranjang: lingkaran `cabai` 16dp, angka mono 10sp.

### 7.8 Struk (komponen khas aplikasi)
Dipakai di layar sukses checkout dan detail pesanan.
```
          TOKO SIMULASI
     Jl. Contoh No. 12, Bandung
- - - - - - - - - - - - - - - - - -
No. Pesanan        TS-20261009-0412
Tanggal            09/10/2026 16:42
- - - - - - - - - - - - - - - - - -
Kopi Gayo Arabika 250g
  2 x 68.000               136.000
Gula Aren Cair 500ml
  1 x 32.500                32.500
- - - - - - - - - - - - - - - - - -
Subtotal                   168.500
Ongkir (simulasi)           12.000
Diskon                     -10.000
TOTAL                   Rp 170.500
- - - - - - - - - - - - - - - - - -
Dibayar dengan Saldo Simulasi
        [ cap LUNAS, daun, miring -8° ]
```
- Seluruh isi pakai `mono`. Latar `surface`, radius 0, tepi bawah bergerigi (zigzag `Path`, gigi 8dp).
- Garis putus-putus digambar dengan `PathEffect.dashPathEffect`.
- Cap "LUNAS": border 2dp `daun`, teks `overline` `daun`, rotasi -8°, opacity 0.85.

### 7.9 Penanda Simulasi
Supaya tidak ada yang mengira ini toko sungguhan:
- Di header Keranjang & Checkout: strip tipis 28dp latar `kunyit` 12% dengan teks `caption`: **"Mode simulasi — tidak ada pembayaran sungguhan."**
- Metode pembayaran hanya "Saldo Simulasi". Tidak ada form kartu kredit/nomor rekening.

---

## 8. Layar & Alur

```
Beranda → Detail Produk → Keranjang → Checkout → Struk (Sukses)
   ↓            ↑                                    ↓
Kategori ───────┘                              Riwayat Pesanan (Akun)
```

| Layar | Isi utama | Catatan desain |
|---|---|---|
| **Beranda** | Search bar, chip kategori, satu blok promo statis, grid "Baru masuk" | Search bar = field biasa, bukan pill besar. Promo: kotak border `ink` dengan teks, tanpa gambar stok |
| **Kategori** | Daftar kategori sebagai list (bukan grid ikon) dengan jumlah produk di kanan (mono) | Contoh: `Bahan Dapur ......... 42` |
| **Detail Produk** | Gambar 4:5 penuh lebar, nama, harga besar, info (berat, asal, stok), deskripsi, bottom bar `[stepper] [Tambah ke keranjang]` | Deskripsi maks 4 baris + "Selengkapnya" |
| **Keranjang** | Strip simulasi, list item (thumbnail 64dp, nama, harga, stepper, hapus), ringkasan, bottom bar total + "Checkout" | Hapus item → snackbar "Dihapus. Batalkan" |
| **Checkout** | Alamat (dummy bisa diedit), opsi kirim (radio: Reguler/Kilat dengan ongkir), pembayaran Saldo Simulasi + sisa saldo, ringkasan | Jika saldo kurang: tombol nonaktif + teks `kunyit` "Saldo kurang Rp 12.000. Isi ulang di Akun." |
| **Struk / Sukses** | Komponen struk §7.8, tombol "Lihat pesanan" & "Belanja lagi" | Satu-satunya layar dengan teks center |
| **Akun** | Saldo (display mono), tombol "Isi ulang saldo simulasi" (+Rp 100.000 / reset), riwayat pesanan, pengaturan tema | Riwayat: list dengan status titik warna |

### Status Layar (wajib di setiap layar data)
- **Loading:** skeleton blok `surfaceSunken` tanpa animasi shimmer berlebihan (pulse opacity 0.6↔1, 900ms) — hanya di grid; layar lain cukup teks `inkMuted` "Memuat…".
- **Kosong:** contoh Keranjang — ikon keranjang outlined 48dp, `title` "Keranjangmu masih kosong", `body` "Produk yang kamu tambahkan akan muncul di sini.", tombol Secondary "Mulai belanja".
- **Error:** `title` yang menjelaskan apa yang gagal + tombol "Coba lagi". Jangan "Oops! Something went wrong 😢".

---

## 9. Bahasa & Copy

- Bahasa Indonesia santai-sopan, sapaan **"kamu"**. Konsisten.
- Kalimat pendek, kata kerja di depan: "Tambah ke keranjang", "Bayar sekarang", "Ubah alamat".
- Tanpa tanda seru berlebihan, tanpa emoji.
- Angka spesifik lebih baik dari kata sifat: "Tiba 2–3 hari" bukan "Pengiriman super cepat!".

Contoh data dummy yang disarankan (simpan di `data/local/seed/`):
`Kopi Gayo Arabika 250 g — Rp 68.000`, `Gula Aren Cair 500 ml — Rp 32.500`, `Sambal Roa Botol 200 g — Rp 45.000`, `Beras Pandan Wangi 5 kg — Rp 78.000`, `Tas Anyaman Pandan — Rp 125.000`, `Keripik Tempe Original 150 g — Rp 18.000`.

---

## 10. Gerak (Motion)

- Durasi: 150ms (mikro), 250ms (transisi layar). Easing `FastOutSlowInEasing`.
- Navigasi antar layar: slide horizontal 24dp + fade. Tidak ada bounce, tidak ada spring berlebihan.
- Tambah ke keranjang: angka badge keranjang "tick" naik (translateY -4dp → 0, 150ms). Tidak ada animasi produk terbang ke ikon keranjang.
- Struk muncul: slide dari atas 32dp seperti keluar dari printer (300ms), lalu cap LUNAS scale 1.2 → 1.0 (150ms).
- Hormati pengaturan "Hapus animasi" sistem.

---

## 11. Aksesibilitas

- Target sentuh minimal 48dp (stepper 32dp diberi padding sentuh tambahan).
- `contentDescription` untuk semua ikon fungsional; gambar produk: nama produk.
- Harga dibacakan wajar: set `semantics { contentDescription = "68 ribu rupiah" }` pada komponen harga.
- Dukung font scale hingga 200% — kartu produk tumbuh ke bawah, tidak memotong teks.
- Warna tidak pernah satu-satunya penanda status (selalu ada teks).

---

## 12. Implementasi Compose

Struktur file tema:
```
ui/theme/
├── Color.kt        // token mentah §3
├── Type.kt         // FontFamily + Typography §4
├── Shape.kt        // sm=4, md=8
├── Spacing.kt      // object Spacing { xs=4, sm=8, md=12, lg=16, xl=24, xxl=32 }
├── TokoColors.kt   // data class TokoColors(paper, surface, ink, inkMuted, line, cabai, daun, kunyit, ...) + LocalTokoColors
└── Theme.kt        // TokoTheme { } — memetakan ke MaterialTheme + menyediakan LocalTokoColors
```

Kerangka:
```kotlin
@Immutable
data class TokoColors(
    val paper: Color, val surface: Color, val surfaceSunken: Color,
    val ink: Color, val inkMuted: Color, val inkFaint: Color, val line: Color,
    val cabai: Color, val onCabai: Color,
    val daun: Color, val kunyit: Color, val tintaBiru: Color,
)

val LightToko = TokoColors(
    paper = Color(0xFFF4F1EA), surface = Color(0xFFFBFAF6), surfaceSunken = Color(0xFFEAE6DC),
    ink = Color(0xFF1A1A17), inkMuted = Color(0xFF5E5B53), inkFaint = Color(0xFF8F8B80),
    line = Color(0xFFD6D1C4), cabai = Color(0xFFC8361A), onCabai = Color.White,
    daun = Color(0xFF2E5E3A), kunyit = Color(0xFFB7800F), tintaBiru = Color(0xFF24497A),
)

val LocalTokoColors = staticCompositionLocalOf { LightToko }

@Composable
fun TokoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val toko = if (darkTheme) DarkToko else LightToko
    val scheme = (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
        primary = toko.cabai, onPrimary = toko.onCabai,
        background = toko.paper, onBackground = toko.ink,
        surface = toko.surface, onSurface = toko.ink,
        surfaceVariant = toko.surfaceSunken, onSurfaceVariant = toko.inkMuted,
        outline = toko.line, error = toko.cabai,
        surfaceTint = Color.Transparent, // matikan tonal elevation ungu
    )
    CompositionLocalProvider(LocalTokoColors provides toko) {
        MaterialTheme(colorScheme = scheme, typography = TokoTypography, shapes = TokoShapes, content = content)
    }
}
```

Aturan kode:
- Komponen **tidak boleh** memakai `Color(0x...)` langsung — selalu lewat `LocalTokoColors.current` / `MaterialTheme`.
- Jangan pakai `Card` default (punya tonal elevation); buat `TokoCard` = `Surface` + `border(1.dp, line)`.
- Setiap komponen punya `@Preview` terang & gelap, dengan data dummy dari §9.

---

## 13. Checklist Review Layar

Sebelum layar dianggap selesai:
- [ ] Hanya satu elemen berwarna `cabai` yang menonjol
- [ ] Semua angka uang pakai `formatRupiah` + font mono
- [ ] Tidak ada shadow, gradien, emoji, atau radius > 8dp
- [ ] Ada state loading, kosong, dan error
- [ ] Copy berbahasa Indonesia, sapaan "kamu", tanpa kalimat marketing kosong
- [ ] Lolos di dark mode dan font scale 200%
- [ ] Penanda simulasi terlihat di alur pembayaran

---

## 14. Status Khusus Flash Sale

Semua teks berikut menyesuaikan perilaku backend di [`docs/api.md`](docs/api.md) §4.2. Prinsipnya: **jujur dan spesifik**, tanpa menyalahkan pengguna dan tanpa janji yang tidak dijamin server.

| Kondisi server | Tampilan | Copy |
|---|---|---|
| Kampanye belum mulai | Hitung mundur mono besar, tombol nonaktif | "Mulai 20.00 WIB" |
| `ACTIVE`, stok tersedia | Harga promo (cabai) + harga normal dicoret + "Sisa ± N" | Tombol "Beli sekarang" |
| `Submitting` | Tombol nonaktif, teks "Memproses…" | Tidak ada spinner dekoratif |
| `202 QUEUED` | Baris status + estimasi posisi | "Kamu dalam antrean. Perkiraan posisi 1.840." |
| `201/200 Reserved` | Layar VA: nomor VA mono + tombol salin + hitung mundur batas bayar | "Bayar sebelum 21.15 WIB. Setelah itu pesanan dibatalkan dan stok dilepas." |
| `SOLD_OUT` | Ikon outlined + kalimat | "Stok promo sudah habis. Kamu tidak dikenai biaya." |
| `ALREADY_PURCHASED` | Arahkan ke order | "Kamu sudah punya pesanan untuk promo ini." |
| `CAMPAIGN_NOT_ACTIVE` | Perbarui kartu kampanye | "Promo ini sedang dijeda atau sudah berakhir." |
| `RATE_LIMITED` | Hitung mundur `Retry-After` | "Terlalu cepat. Coba lagi dalam 3 detik." |
| Timeout jaringan | Pertahankan state `Submitting` lalu cek ulang dengan key yang sama | "Memeriksa pesananmu…" (jangan tampilkan error yang menyuruh menekan Beli lagi) |
| Order `EXPIRED` | Status teks `inkMuted` | "Waktu pembayaran habis. Pesanan dibatalkan." |
| `REFUND_REQUIRED/REFUNDED` | Status `tinta-biru` | "Pembayaranmu masuk setelah stok dilepas. Dana dikembalikan otomatis." |
| `VOIDED` | Status `kunyit` + penjelasan | "Pesanan ini dibatalkan platform. Dana dikembalikan penuh." |

Aturan tambahan:
- Label stok memakai kata **"perkiraan"** atau tanda "±"; jangan menampilkan angka pasti.
- Hitung mundur memakai waktu server terkoreksi ([`docs/backend.md`](docs/backend.md) B.3).
- Tombol "Beli" tidak boleh bisa ditekan dua kali, namun aman bila tertekan karena server idempoten.
