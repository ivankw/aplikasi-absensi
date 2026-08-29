package com.example.mysimpleapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

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
// 2. PROFILE STORAGE & KONVERSI TANDA TANGAN
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

// Mengonversi goresan garis ke Gambar PNG transparan dengan skala otomatis
fun convertPathsToBase64(paths: List<List<Offset>>): String {
    if (paths.isEmpty()) return ""
    val allPoints = paths.flatten()
    if (allPoints.isEmpty()) return ""

    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val minY = allPoints.minOf { it.y }
    val maxY = allPoints.maxOf { it.y }

    val padding = 20f
    val width = max((maxX - minX + padding * 2).toInt(), 120)
    val height = max((maxY - minY + padding * 2).toInt(), 60)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val paint = Paint().apply {
        color = AndroidColor.BLACK
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    paths.forEach { stroke ->
        if (stroke.isNotEmpty()) {
            val path = AndroidPath()
            path.moveTo(stroke.first().x - minX + padding, stroke.first().y - minY + padding)
            for (i in 1 until stroke.size) {
                path.lineTo(stroke[i].x - minX + padding, stroke[i].y - minY + padding)
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
// 3. EXPORT KE EXCEL DENGAN FOTO TANDA TANGAN
// ==========================================

fun exportToExcelTemplate(
    context: Context,
    profile: UserProfile,
    records: List<AttendanceRecord>
) {
    val calendar = Calendar.getInstance()
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)
    val totalDaysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

    val periodString = SimpleDateFormat("MMMM yyyy", Locale("id", "ID")).format(calendar.time)

    val htmlContent = StringBuilder().apply {
        append("<html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:x='urn:schemas-microsoft-com:office:excel' xmlns='http://www.w3.org/TR/REC-html40'>")
        append("<head><meta charset='utf-8'>")
        append("<!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>ABSENSI</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]-->")
        append("<style>")
        append("body { font-family: Arial, sans-serif; font-size: 10pt; }")
        append("table { border-collapse: collapse; width: 100%; }")
        append(".header-cell { background-color: #1B365D; color: #FFFFFF; font-weight: bold; text-align: center; vertical-align: middle; border: 1px solid #000000; height: 32px; font-size: 10pt; }")
        append(".data-cell { text-align: center; vertical-align: middle; border: 1px solid #000000; height: 28px; font-size: 10pt; }")
        append("</style>")
        append("</head><body>")
        append("<table>")

        // Baris 1 - 5: Kosong
        for (i in 1..5) {
            append("<tr style='height:18px;'><td colspan='7'></td></tr>")
        }

        // Baris 6: Nama Pegawai & Periode Bulan
        append("<tr style='height:24px;'>")
        append("<td colspan='2' style='font-weight:bold; font-size:11pt;'>Nama Pegawai</td>")
        append("<td colspan='3' style='font-weight:bold; font-size:11pt;'>${profile.name}</td>")
        append("<td style='font-weight:bold; font-size:11pt; text-align:right;'>Periode Bulan</td>")
        append("<td style='font-weight:bold; font-size:11pt; text-align:center;'>$periodString</td>")
        append("</tr>")

        // Baris 7: Jabatan / Divisi & NIK / ID
        append("<tr style='height:24px;'>")
        append("<td colspan='2' style='font-weight:bold; font-size:11pt;'>Jabatan / Divisi</td>")
        append("<td colspan='3' style='font-size:11pt;'>${profile.division}</td>")
        append("<td style='font-weight:bold; font-size:11pt; text-align:right;'>NIK / ID</td>")
        append("<td style='font-weight:bold; font-size:11pt; text-align:center;'>${profile.nik}</td>")
        append("</tr>")

        // Baris 8: Kosong
        append("<tr style='height:18px;'><td colspan='7'></td></tr>")

        // Baris 9: Header Tabel
        append("<tr>")
        append("<th class='header-cell' style='width:110px;'>Tanggal</th>")
        append("<th class='header-cell' style='width:100px;'>Jadwal</th>")
        append("<th class='header-cell' style='width:95px;'>Jam Masuk</th>")
        append("<th class='header-cell' style='width:95px;'>Jam Keluar</th>")
        append("<th class='header-cell' style='width:95px;'>Durasi Kerja</th>")
        append("<th class='header-cell' style='width:140px;'>Tanda Tangan Staff</th>")
        append("<th class='header-cell' style='width:140px;'>Tanda Tangan SPV</th>")
        append("</tr>")

        // Baris 10 s.d. Akhir Bulan: Tabel dengan Foto Tanda Tangan Staff
        for (day in 1..totalDaysInMonth) {
            val dateKey = String.format(Locale.getDefault(), "%02d/%02d/%04d", day, month + 1, year)
            val dayRecords = records.filter { it.date == dateKey }

            val inRecord = dayRecords.firstOrNull { it.type.contains("LOGIN") }
            val outRecord = dayRecords.lastOrNull { it.type.contains("LOGOUT") }

            val checkIn = inRecord?.time ?: ""
            val checkOut = outRecord?.time ?: ""
            val schedule = if (inRecord != null) "Normal" else ""
            
            // Lampirkan gambar tanda tangan langsung jika sudah absen masuk
            val staffSignImg = if (inRecord != null && profile.signatureBase64.isNotBlank()) {
                "<img src='data:image/png;base64,${profile.signatureBase64}' style='max-height:22px; max-width:80px; vertical-align:middle;' />"
            } else {
                ""
            }

            append("<tr>")
            append("<td class='data-cell'>$dateKey</td>")
            append("<td class='data-cell'>$schedule</td>")
            append("<td class='data-cell'>$checkIn</td>")
            append("<td class='data-cell'>$checkOut</td>")
            append("<td class='data-cell'></td>")
            append("<td class='data-cell'>$staffSignImg</td>")
            append("<td class='data-cell'></td>")
            append("</tr>")
        }

        // Baris Pemisah
        append("<tr style='height:20px;'><td colspan='7'></td></tr>")

        // Footer Baris 42: Judul Pengesahan
        append("<tr>")
        append("<td colspan='3' style='text-align:center; font-size:11pt;'>Dibuat oleh / Diisi oleh,</td>")
        append("<td colspan='4' style='text-align:center; font-size:11pt;'>Diperiksa & Disetujui oleh,</td>")
        append("</tr>")

        // Footer Baris 43-44: Lampirkan Foto Tanda Tangan Pengesahan
        append("<tr style='height:55px;'>")
        append("<td colspan='3' style='text-align:center; vertical-align:middle;'>")
        if (profile.signatureBase64.isNotBlank()) {
            append("<img src='data:image/png;base64,${profile.signatureBase64}' style='max-height:45px; max-width:130px; vertical-align:middle;' />")
        }
        append("</td>")
        append("<td colspan='4'></td>")
        append("</tr>")

        // Footer Baris 45: NIK
        append("<tr>")
        append("<td colspan='3' style='text-align:center; font-size:10pt;'>${profile.nik}</td>")
        append("<td colspan='4' style='text-align:center; font-size:10pt;'>-</td>")
        append("</tr>")

        // Footer Baris 46: Nama
        append("<tr>")
        append("<td colspan='3' style='text-align:center; font-weight:bold; font-size:11pt;'>${profile.name}</td>")
        append("<td colspan='4' style='text-align:center; font-weight:bold; font-size:11pt;'>Supervisor / Atasan Langsung</td>")
        append("</tr>")

        // Footer Baris 47: Sub-Keterangan
        append("<tr>")
        append("<td colspan='3' style='text-align:center; font-size:9pt; color:#555555;'>Nama Staff / Pegawai</td>")
        append("<td colspan='4' style='text-align:center; font-size:9pt; color:#555555;'>Supervisor / Atasan Langsung</td>")
        append("</tr>")

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
            "com.example.mysimpleapp.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.ms-excel"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Rekap Presensi - ${profile.name}")
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

            Button(
                onClick = { exportToExcelTemplate(context, profile, records) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B365D)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("📊 Export ke Excel Sesuai Template", fontWeight = FontWeight.Bold)
            }

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
