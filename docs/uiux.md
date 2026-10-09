# UI/UX Design System & User Flow

> Spesifikasi desain lengkap (warna, tipografi, komponen, layar, copy, motion) ada di **[`/DESIGN.md`](../DESIGN.md)**. Dokumen itu sumber kebenaran tunggal; palet ungu default Material 3 dan dynamic color **tidak dipakai**.

## Ringkasan
- Arah visual: "Struk & Rak Toko" — latar kertas hangat, tinta hitam, satu aksen merah cabai, angka monospace.
- Font: Instrument Sans (UI) + JetBrains Mono (angka/harga), dibundel di `res/font/`.
- Radius 4/8dp, elevation 0, pemisah border 1dp.

## Aksesibilitas
- Target sentuh minimal 48dp x 48dp.
- `contentDescription` pada semua elemen visual interaktif.
- Kontras teks >= 4.5:1.

## Status Layar
Setiap layar data wajib punya: Loading, Success, Empty, Error (detail di DESIGN.md §8).
