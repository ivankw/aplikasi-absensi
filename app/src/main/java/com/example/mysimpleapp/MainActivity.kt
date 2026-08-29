package com.example.mysimpleapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==========================================
// 1. DATABASE ROOM
// ==========================================

@Entity(tableName = "attendance_table")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val nik: String,
    val division: String,
    val type: String,       // "LOGIN (MASUK)" atau "LOGOUT (PULANG)"
    val date: String,       // "dd/MM/yyyy"
    val dateDisplay: String,// "EEEE, dd MMMM yyyy"
    val time: String,       // "HH:mm:ss"
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_table ORDER BY timestamp ASC")
    fun getAllAttendancesAsc(): Flow<List<AttendanceRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(record: AttendanceRecord)

    @Query("DELETE FROM attendance_table")
    suspend fun deleteAll()
}

@Database(entities = [AttendanceRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "attendance_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 2. PROFILE MANAGER & HELPER TANDA TANGAN
// ==========================================

data class UserProfile(
    val name: String,
    val nik: String,
    val division: String,
    val signatureBase64: String
)

class ProfileManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_profile_prefs", Context.MODE_PRIVATE)

    fun isProfileSet(): Boolean {
        return !prefs.getString("user_name", "").isNullOrBlank() &&
               !prefs.getString("user_nik", "").isNullOrBlank()
    }

    fun saveProfile(name: String, nik: String, division: String, signatureBase64: String) {
        prefs.edit()
            .putString("user_name", name.trim())
            .putString("user_nik", nik.trim())
            .putString("user_division", division.trim())
            .putString("user_signature", signatureBase64)
            .apply()
    }

    fun getProfile(): UserProfile {
        return UserProfile(
            name = prefs.getString("user_name", "") ?: "",
            nik = prefs.getString("user_nik", "") ?: "",
            division = prefs.getString("user_division", "PBH") ?: "PBH",
            signatureBase64 = prefs.getString("user_signature", "") ?: ""
        )
    }

    fun clearProfile() {
        prefs.edit().clear().apply()
    }
}

fun convertPathsToBase64(paths: List<List<Offset>>, width: Int = 400, height: Int = 200): String {
    if (paths.isEmpty()) return ""
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 6f
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = android.graphics.Paint.Cap.ROUND
        isAntiAlias = true
    }

    paths.forEach { stroke ->
        if (stroke.isNotEmpty()) {
            val path = android.graphics.Path()
            path.moveTo(stroke.first().x, stroke.first().y)
            for (i in 1 until stroke.size) {
                path.lineTo(stroke[i].x, stroke[i].y)
            }
            canvas.drawPath(path, paint)
        }
    }

    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

fun decodeBase64ToBitmap(base64Str: String): ImageBitmap? {
    return try {
        if (base64Str.isBlank()) return null
        val decodedBytes = Base64.decode(base64Str, Base64.NO_WRAP)
        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

// ==========================================
// 3. EXPORT KE EXCEL (TANPA DURASI KERJA)
// ==========================================

data class DailyAttendanceSummary(
    val date: String,
    val schedule: String = "Normal",
    val checkIn: String = "-",
    val checkOut: String = "-",
    val staffSign: String = "[Sudah TTD]",
    val spvSign: String = ""
)

fun exportToExcelTemplate(
    context: Context,
    profile: UserProfile,
    records: List<AttendanceRecord>
) {
    if (records.isEmpty()) {
        Toast.makeText(context, "Tidak ada data untuk diekspor!", Toast.LENGTH_SHORT).show()
        return
    }

    val currentMonthPeriod = SimpleDateFormat("MMMM yyyy", Locale("id", "ID")).format(Date())

    // Kelompokkan data harian
    val grouped = records.groupBy { it.date }
    val summaries = grouped.map { (dateStr, dayRecords) ->
        val inRecord = dayRecords.firstOrNull { it.type.contains("LOGIN") }
        val outRecord = dayRecords.lastOrNull { it.type.contains("LOGOUT") }

        DailyAttendanceSummary(
            date = dateStr,
            schedule = "Normal",
            checkIn = inRecord?.time ?: "-",
            checkOut = outRecord?.time ?: "-",
            staffSign = if (profile.signatureBase64.isNotBlank()) "[TERVERIFIKASI]" else "[TTD]",
            spvSign = ""
        )
    }

    // Format Spreadsheet 6 Kolom
    val htmlContent = StringBuilder().apply {
        append("<html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:x='urn:schemas-microsoft-com:office:excel' xmlns='http://www.w3.org/TR/REC-html40'>")
        append("<head><meta charset='utf-8'><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>ABSENSI</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]--></head>")
        append("<body>")
        append("<table border='1' cellspacing='0' cellpadding='5' style='border-collapse:collapse; font-family:Arial, sans-serif; font-size:11pt;'>")
        
        // Header Profil
        append("<tr><td colspan='2' style='font-weight:bold;'>Nama Pegawai</td><td colspan='2'>${profile.name}</td><td style='font-weight:bold;'>Periode Bulan</td><td>$currentMonthPeriod</td></tr>")
        append("<tr><td colspan='2' style='font-weight:bold;'>Jabatan / Divisi</td><td colspan='2'>${profile.division}</td><td style='font-weight:bold;'>NIK / ID</td><td>${profile.nik}</td></tr>")
        append("<tr><td colspan='6'></td></tr>")

        // Header Kolom Tabel (6 Kolom)
        append("<tr style='background-color:#E0E0E0; font-weight:bold; text-align:center;'>")
        append("<th>Tanggal</th><th>Jadwal</th><th>Jam Masuk</th><th>Jam Keluar</th><th>Tanda Tangan Staff</th><th>Tanda Tangan SPV</th>")
        append("</tr>")

        // Data Baris
        summaries.forEach { row ->
            append("<tr style='text-align:center;'>")
            append("<td>${row.date}</td>")
            append("<td>${row.schedule}</td>")
            append("<td>${row.checkIn}</td>")
            append("<td>${row.checkOut}</td>")
            append("<td>${row.staffSign}</td>")
            append("<td>${row.spvSign}</td>")
            append("</tr>")
        }

        // Footer Pengesahan
        append("<tr><td colspan='6'></td></tr>")
        append("<tr><td colspan='3' style='text-align:center; font-weight:bold;'>Dibuat oleh / Diisi oleh,</td><td colspan='3' style='text-align:center; font-weight:bold;'>Diperiksa & Disetujui oleh,</td></tr>")
        append("<tr style='height:50px;'><td colspan='3' style='text-align:center;'>${if (profile.signatureBase64.isNotBlank()) "[Tanda Tangan Digital Terlampir]" else ""}</td><td colspan='3'></td></tr>")
        append("<tr><td colspan='3' style='text-align:center;'>${profile.nik}</td><td colspan='3' style='text-align:center;'>-</td></tr>")
        append("<tr><td colspan='3' style='text-align:center; font-weight:bold;'>${profile.name}</td><td colspan='3' style='text-align:center; font-weight:bold;'>Supervisor / Atasan Langsung</td></tr>")
        append("<tr><td colspan='3' style='text-align:center; font-size:9pt;'>Nama Staff / Pegawai</td><td colspan='3' style='text-align:center; font-size:9pt;'>Supervisor / Atasan Langsung</td></tr>")

        append("</table>")
        append("</body></html>")
    }.toString()

    try {
        val fileName = "ABSENSI_${profile.name.replace(" ", "_")}_${System.currentTimeMillis()}.xls"
        val cachePath = File(context.cacheDir, "exports")
        cachePath.mkdirs()
        val file = File(cachePath, fileName)
        val fos = FileOutputStream(file)
        fos.write(htmlContent.toByteArray(Charsets.UTF_8))
        fos.close()

        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.ms-excel"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Laporan Rekap Absensi - ${profile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Kirim / Buka File Excel"))
    } catch (e: Exception) {
        Toast.makeText(context, "Gagal mengekspor file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ==========================================
// 4. ACTIVITY & SCREENS
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)
        val dao = database.attendanceDao()
        val profileManager = ProfileManager(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isProfileComplete by remember { mutableStateOf(profileManager.isProfileSet()) }

                    if (!isProfileComplete) {
                        OnboardingScreen(profileManager) {
                            isProfileComplete = true
                        }
                    } else {
                        AttendanceScreen(dao, profileManager) {
                            profileManager.clearProfile()
                            isProfileComplete = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(profileManager: ProfileManager, onComplete: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var nik by remember { mutableStateOf("") }
    var division by remember { mutableStateOf("PBH") }

    var paths by remember { mutableStateOf(listOf<List<Offset>>()) }
    var currentPath by remember { mutableStateOf(listOf<Offset>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Registrasi Data Awal", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Isi data & tanda tangan untuk mencetak laporan Excel",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nama Pegawai / Lengkap") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = nik,
            onValueChange = { nik = it },
            label = { Text("NIK / ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = division,
            onValueChange = { division = it },
            label = { Text("Jabatan / Divisi") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tanda Tangan Staff:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (paths.isNotEmpty() || currentPath.isNotEmpty()) {
                TextButton(onClick = {
                    paths = emptyList()
                    currentPath = emptyList()
                }) {
                    Text("Hapus / Ulang", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> currentPath = listOf(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPath = currentPath + change.position
                        },
                        onDragEnd = {
                            paths = paths + listOf(currentPath)
                            currentPath = emptyList()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (paths.isEmpty() && currentPath.isEmpty()) {
                Text("Goreskan tanda tangan di sini", color = Color.LightGray, fontSize = 13.sp)
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                paths.forEach { stroke ->
                    for (i in 0 until stroke.size - 1) {
                        drawLine(
                            color = Color.Black,
                            start = stroke[i],
                            end = stroke[i + 1],
                            strokeWidth = 6f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                if (currentPath.size > 1) {
                    for (i in 0 until currentPath.size - 1) {
                        drawLine(
                            color = Color.Black,
                            start = currentPath[i],
                            end = currentPath[i + 1],
                            strokeWidth = 6f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (name.isBlank() || nik.isBlank() || division.isBlank()) {
                    Toast.makeText(context, "Semua data wajib diisi!", Toast.LENGTH_SHORT).show()
                } else if (paths.isEmpty()) {
                    Toast.makeText(context, "Harap bubuhkan tanda tangan Anda!", Toast.LENGTH_SHORT).show()
                } else {
                    val signatureBase64 = convertPathsToBase64(paths)
                    profileManager.saveProfile(name, nik, division, signatureBase64)
                    Toast.makeText(context, "Profil berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Simpan & Buka Form Absen", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    dao: AttendanceDao,
    profileManager: ProfileManager,
    onResetProfile: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = profileManager.getProfile()
    val signatureBitmap = remember(profile.signatureBase64) { decodeBase64ToBitmap(profile.signatureBase64) }

    val records by dao.getAllAttendancesAsc().collectAsState(initial = emptyList())
    val groupedRecords = records.groupBy { it.dateDisplay }

    fun recordAttendance(type: String) {
        val now = Date()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateDisplayFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val newRecord = AttendanceRecord(
            name = profile.name,
            nik = profile.nik,
            division = profile.division,
            type = type,
            date = dateFormat.format(now),
            dateDisplay = dateDisplayFormat.format(now),
            time = timeFormat.format(now)
        )

        scope.launch {
            dao.insertAttendance(newRecord)
        }
        Toast.makeText(context, "Berhasil $type pada ${newRecord.time}", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Presensi Harian", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    TextButton(onClick = onResetProfile) {
                        Text("Ganti Akun", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card Profil Karyawan & Tanda Tangan
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("NIK: ${profile.nik}", fontSize = 13.sp)
                        Text("Divisi: ${profile.division}", fontSize = 13.sp)
                    }

                    if (signatureBitmap != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TTD Staff", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(
                                modifier = Modifier
                                    .size(width = 80.dp, height = 45.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = signatureBitmap,
                                    contentDescription = "Tanda Tangan",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Tombol Login & Logout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { recordAttendance("LOGIN (MASUK)") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("LOGIN\n(MASUK)", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }

                Button(
                    onClick = { recordAttendance("LOGOUT (PULANG)") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("LOGOUT\n(PULANG)", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }

            // Tombol Export Sesuai Template
            Button(
                onClick = { exportToExcelTemplate(context, profile, records) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D6F42)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("📊 Export ke Excel Sesuai Template", fontWeight = FontWeight.Bold)
            }

            // Header Riwayat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat Presensi (${records.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (records.isNotEmpty()) {
                    TextButton(onClick = { scope.launch { dao.deleteAll() } }) {
                        Text("Hapus Riwayat", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Daftar Riwayat
            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Belum ada data presensi", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groupedRecords.forEach { (dateHeader, dayRecords) ->
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = dateHeader,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        items(dayRecords, key = { it.id }) { record ->
                            val isLogin = record.type.contains("LOGIN")
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = record.type,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isLogin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${record.name} (${record.division})",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = record.time,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
