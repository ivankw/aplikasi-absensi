package com.example.mysimpleapp

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
// 1. STRUKTUR ROOM DATABASE (SQLite)
// ==========================================

@Entity(tableName = "attendance_table")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String,      // "Masuk" atau "Pulang"
    val status: String,    // "Hadir", "Izin", "Sakit"
    val timestamp: String
)

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_table ORDER BY id DESC")
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
// 2. TAMPILAN APLIKASI (UI)
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)
        val dao = database.attendanceDao()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AttendanceScreen(dao)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(dao: AttendanceDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("Masuk") }
    var selectedStatus by remember { mutableStateOf("Hadir") }

    // Membaca data secara otomatis dan realtime dari database SQLite
    val records by dao.getAllAttendances().collectAsState(initial = emptyList())

    val statusOptions = listOf("Hadir", "Izin", "Sakit")
    val typeOptions = listOf("Masuk", "Pulang")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Presensi Mandiri (Offline DB)", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
            // Form Presensi
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Form Presensi", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nama Lengkap") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text("Tipe Presensi:", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        typeOptions.forEach { type ->
                            FilterChip(
                                selected = (selectedType == type),
                                onClick = { selectedType = type },
                                label = { Text(type) }
                            )
                        }
                    }

                    Text("Keterangan:", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statusOptions.forEach { status ->
                            FilterChip(
                                selected = (selectedStatus == status),
                                onClick = { selectedStatus = status },
                                label = { Text(status) }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                Toast.makeText(context, "Nama tidak boleh kosong!", Toast.LENGTH_SHORT).show()
                            } else {
                                val currentTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                                val newRecord = AttendanceRecord(
                                    name = name.trim(),
                                    type = selectedType,
                                    status = selectedStatus,
                                    timestamp = currentTime
                                )
                                // Menyimpan data ke database permanen
                                scope.launch {
                                    dao.insertAttendance(newRecord)
                                }
                                Toast.makeText(context, "Data tersimpan permanen di HP!", Toast.LENGTH_SHORT).show()
                                name = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simpan Presensi")
                    }
                }
            }

            // Header Riwayat dan Tombol Hapus Semua
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat Tersimpan (${records.size})", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (records.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            dao.deleteAll()
                        }
                    }) {
                        Text("Hapus Semua", color = MaterialTheme.colorScheme.error)
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
                    Text("Belum ada data di memori", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.id }) { item ->
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
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        text = "${item.type} • ${item.status}",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = item.timestamp,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
