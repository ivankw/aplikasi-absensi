package com.example.mysimpleapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
// 2. PROFILE STORAGE (SHARRED PREFERENCES)
// ==========================================

class ProfileManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_profile_prefs", Context.MODE_PRIVATE)

    fun isProfileSet(): Boolean {
        return !prefs.getString("user_name", "").isNullOrBlank() &&
               !prefs.getString("user_nik", "").isNullOrBlank()
    }

    fun saveProfile(name: String, nik: String, division: String) {
        prefs.edit()
            .putString("user_name", name.trim())
            .putString("user_nik", nik.trim())
            .putString("user_division", division.trim())
            .apply()
    }

    fun getProfile(): Triple<String, String, String> {
        return Triple(
            prefs.getString("user_name", "") ?: "",
            prefs.getString("user_nik", "") ?: "",
            prefs.getString("user_division", "PBH") ?: "PBH"
        )
    }

    fun clearProfile() {
        prefs.edit().clear().apply()
    }
}

// ==========================================
// 3. ACTIVITY UTAMA & SCREEN ROUTING
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
                        // Tampilan Pengisian Data Awal
                        OnboardingScreen(profileManager) {
                            isProfileComplete = true
                        }
                    } else {
                        // Tampilan Absensi & Riwayat
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
// 4. UI: ONBOARDING / SETUP PROFIL PERTAMA KALI
// ==========================================

@Composable
fun OnboardingScreen(profileManager: ProfileManager, onComplete: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var nik by remember { mutableStateOf("") }
    var division by remember { mutableStateOf("PBH") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Registrasi Data Awal", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Masukkan identitas Anda untuk memulai absensi",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nama Lengkap") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = nik,
            onValueChange = { nik = it },
            label = { Text("NIK / ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = division,
            onValueChange = { division = it },
            label = { Text("Jabatan / Divisi") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isBlank() || nik.isBlank() || division.isBlank()) {
                    Toast.makeText(context, "Semua data wajib diisi!", Toast.LENGTH_SHORT).show()
                } else {
                    profileManager.saveProfile(name, nik, division)
                    Toast.makeText(context, "Profil berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Simpan & Lanjutkan", fontSize = 16.sp)
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
    val (name, nik, division) = profileManager.getProfile()

    // Membaca seluruh data absensi dari SQLite
    val records by dao.getAllAttendances().collectAsState(initial = emptyList())

    // Mengelompokkan data berdasarkan tanggal (Riwayat by Day)
    val groupedRecords = records.groupBy { it.date }

    fun recordAttendance(type: String) {
        val now = Date()
        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val currentDate = dateFormat.format(now)
        val currentTime = timeFormat.format(now)

        val newRecord = AttendanceRecord(
            name = name,
            nik = nik,
            division = division,
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card Data Profil Karyawan
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("NIK / ID : $nik", fontSize = 14.sp)
                    Text("Divisi   : $division", fontSize = 14.sp)
                }
            }

            // Tombol Login (Masuk) dan Logout (Pulang)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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

            // Header Riwayat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat Presensi", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (records.isNotEmpty()) {
                    TextButton(onClick = { scope.launch { dao.deleteAll() } }) {
                        Text("Hapus Riwayat", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Riwayat yang Dikelompokkan Berdasarkan Hari (by Day)
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    groupedRecords.forEach { (dateHeader, dayRecords) ->
                        item {
                            // Header Tanggal / Hari
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = dateHeader,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
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
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = record.type,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isLogin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "${record.name} (${record.division})",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = record.time,
                                        fontSize = 14.sp,
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
