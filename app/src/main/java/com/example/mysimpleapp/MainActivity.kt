package com.example.mysimpleapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==========================================
// 1. DATABASE & MODEL DATA
// ==========================================

@Entity(tableName = "attendance_table")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val nik: String,
    val division: String,
    val type: String,       // "LOGIN (MASUK)" atau "LOGOUT (PULANG)"
    val date: String,       // Format: "Senin, 01/01/2026"
    val time: String,       // Format: "08:00:00"
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_table ORDER BY timestamp DESC")
    fun getAllAttendances(): Flow<List<AttendanceRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(record: AttendanceRecord)

    @Query("DELETE FROM attendance_table")
    suspend fun deleteAll()
}

@Database(entities = [AttendanceRecord::class], version = 1, exportSchema = false)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 2. PROFILE STORAGE & HELPER TANDA TANGAN
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

// Konversi goresan garis ke Gambar Base64
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

// Konversi string Base64 kembali ke ImageBitmap Compose
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
// 3. ACTIVITY UTAMA & ROUTING
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

// ==========================================
// 4. UI: ONBOARDING + KOLOM TANDA TANGAN
// ==========================================

@Composable
fun OnboardingScreen(profileManager: ProfileManager, onComplete: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var nik by remember { mutableStateOf("") }
    var division by remember { mutableStateOf("PBH") }

    // State untuk menyimpan goresan garis tanda tangan
    var paths by remember { mutableStateOf(listOf<List<Offset>>()) }
    var currentPath by remember { mutableStateOf(listOf<Offset>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Registrasi Profil Awal", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Isi data & tanda tangan untuk mulai menggunakan aplikasi",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nama Lengkap") },
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

        // Label Kolom Tanda Tangan
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tanda Tangan Digital:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (paths.isNotEmpty() || currentPath.isNotEmpty()) {
                TextButton(onClick = {
                    paths = emptyList()
                    currentPath = emptyList()
                }) {
                    Text("Hapus / Ulang", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Kanvas Tanda Tangan
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPath = listOf(offset)
                        },
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
                Text(
                    "Goreskan tanda tangan di sini",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Gambar garis yang sudah selesai
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
                // Gambar garis yang sedang disentuh
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
                    Toast.makeText(context, "Nama, NIK, dan Divisi wajib diisi!", Toast.LENGTH_SHORT).show()
                } else if (paths.isEmpty()) {
                    Toast.makeText(context, "Harap bubuhkan tanda tangan Anda!", Toast.LENGTH_SHORT).show()
                } else {
                    val signatureBase64 = convertPathsToBase64(paths)
                    profileManager.saveProfile(name, nik, division, signatureBase64)
                    Toast.makeText(context, "Profil & Tanda Tangan berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Simpan & Masuk ke Absensi", fontSize = 16.sp)
        }
    }
}

// ==========================================
// 5. UI: HALAMAN ABSENSI & RIWAYAT BY DAY
// ==========================================

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

    val records by dao.getAllAttendances().collectAsState(initial = emptyList())
    val groupedRecords = records.groupBy { it.date }

    fun recordAttendance(type: String) {
        val now = Date()
        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val currentDate = dateFormat.format(now)
        val currentTime = timeFormat.format(now)

        val newRecord = AttendanceRecord(
            name = profile.name,
            nik = profile.nik,
            division = profile.division,
            type = type,
            date = currentDate,
            time = currentTime
        )

        scope.launch {
            dao.insertAttendance(newRecord)
        }
        Toast.makeText(context, "Berhasil $type pada $currentTime", Toast.LENGTH_SHORT).show()
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
            // Card Data Profil Karyawan & Tanda Tangan
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

                    // Tampilkan Tanda Tangan Pengguna
                    if (signatureBitmap != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TTD Pengguna", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(
                                modifier = Modifier
                                    .size(width = 90.dp, height = 50.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = signatureBitmap,
                                    contentDescription = "Tanda Tangan Pengguna",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Tombol Login (Masuk) dan Logout (Pulang)
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

            // Header Riwayat Presensi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat Presensi", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (records.isNotEmpty()) {
                    TextButton(onClick = { scope.launch { dao.deleteAll() } }) {
                        Text("Hapus Riwayat", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Daftar Riwayat yang Dikelompokkan Berdasarkan Hari
            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Belum ada riwayat absensi", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
