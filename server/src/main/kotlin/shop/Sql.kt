package shop

/** Perbedaan dialek kecil antara H2 (mode MySQL, dipakai simulasi) dan MySQL 8 (diverifikasi lewat Docker). */
object Sql {
    @Volatile var mysql = false

    /** Penjumlahan interval pada jam database. [amount] boleh berupa angka atau placeholder `?`. */
    fun dateAdd(unit: String, amount: String): String =
        if (mysql) "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL $amount $unit)"
        else "DATEADD('$unit', $amount, CURRENT_TIMESTAMP(3))"
}
