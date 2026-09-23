from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "PhoneMindAndroid")
app = root / "app"
src = app / "src/main/java/com/aviv/phonemind"
overlay = Path("phonemind_v032_overlay")

gradle = app / "build.gradle.kts"
s = gradle.read_text()
s = s.replace('versionCode = 4', 'versionCode = 5')
s = s.replace('versionName = "0.3.1"', 'versionName = "0.3.2"')
gradle.write_text(s)

for name in ("BackupStateStore.kt", "BackupRecovery.kt", "DriveBackupWorker.kt"):
    shutil.copy2(overlay / name, src / name)

manifest = app / "src/main/AndroidManifest.xml"
s = manifest.read_text()
if 'android.permission.RECEIVE_BOOT_COMPLETED' not in s:
    s = s.replace(
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />',
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n'
        '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />'
    )

receiver = '''
        <receiver
            android:name=".BackupBootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
'''
if 'android:name=".BackupBootReceiver"' not in s:
    marker = '        <activity\n            android:name=".MainActivity"'
    if marker not in s:
        raise SystemExit("MainActivity manifest marker not found")
    s = s.replace(marker, receiver + marker)

manifest.write_text(s)

repo = src / "DriveBackupRepository.kt"
s = repo.read_text()
old = 'val selected = sourceItems.filter { categories.contains(it.category) && File(it.path).isFile }'
new = '''val selected = sourceItems.asSequence()
            .filter { categories.contains(it.category) }
            .mapNotNull { item ->
                val f = File(item.path)
                if (!f.isFile) null
                else if (
                    f.length() != item.size ||
                    (item.modified > 0L && f.lastModified() != item.modified)
                ) {
                    item.copy(
                        size = f.length().coerceAtLeast(0L),
                        modified = f.lastModified()
                    )
                } else item
            }
            .toList()'''
if old not in s:
    raise SystemExit("Drive repository selection marker not found")
s = s.replace(old, new)
repo.write_text(s)

main = src / "MainActivity.kt"
s = main.read_text()

if 'private val backupStateStore by lazy' not in s:
    marker = '    private val workManager by lazy { WorkManager.getInstance(applicationContext) }'
    if marker not in s:
        raise SystemExit("WorkManager field marker not found")
    s = s.replace(
        marker,
        marker + '\n'
        '    private val backupStateStore by lazy { BackupStateStore(applicationContext) }'
    )

old = '                val request=DriveBackupWorker.request(categories,deleteAfter)'
new = '''                backupStateStore.activate(categories, deleteAfter)
                BackupRecoveryScheduler.ensureScheduled(this@MainActivity)
                val request=DriveBackupWorker.request(categories,deleteAfter)'''
if old not in s:
    raise SystemExit("Drive request marker not found")
s = s.replace(old, new)

start = s.find('    private fun restoreDriveBackupWork()')
end = s.find('    private fun observeDriveBackupWork', start)
if start < 0 or end < 0:
    raise SystemExit("restoreDriveBackupWork block not found")

replacement = '''    private fun restoreDriveBackupWork() {
        BackupRecoveryScheduler.ensureScheduled(this)
        if (backupStateStore.isActive()) {
            BackupRecoveryScheduler.kick(this)
        }

        workManager
            .getWorkInfosForUniqueWorkLiveData(DriveBackupWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val running = infos.firstOrNull { !it.state.isFinished }
                if (running != null) {
                    observeDriveBackupWork(running.id)
                } else if (backupStateStore.isActive()) {
                    operation = OperationState(
                        "מגבה ל‑Google Drive",
                        backupStateStore.lastMessage() ?: "ממתין ל-Wi-Fi…",
                        null,
                        cancellable = true
                    )
                    BackupRecoveryScheduler.kick(this)
                }
            }
    }

'''
s = s[:start] + replacement + s[end:]

s = s.replace('"ממתין לחיבור יציב…"', '"ממתין ל-Wi-Fi…"')

failed_start = s.find('                WorkInfo.State.FAILED->')
gmail_after = s.find('            }\n        }\n    }\n    private fun reloadStorageStateAfterWorker', failed_start)
if failed_start < 0 or gmail_after < 0:
    raise SystemExit("WorkInfo terminal-state block not found")

terminal_replacement = '''                WorkInfo.State.FAILED->{
                    if (backupStateStore.isActive()) {
                        activeBackupWorkId = null
                        operation = OperationState(
                            "מגבה ל‑Google Drive",
                            backupStateStore.lastMessage() ?: "ממתין לניסיון נוסף…",
                            null,
                            cancellable = true
                        )
                        BackupRecoveryScheduler.kick(this)
                    } else {
                        val e=info.outputData.getString(DriveBackupWorker.KEY_ERROR).orEmpty()
                        finishDriveWorkUi(
                            info.id,
                            e.ifBlank{"הגיבוי נכשל. הקבצים שלא אומתו נשארו בטלפון."}
                        )
                    }
                }
                WorkInfo.State.CANCELLED->{
                    if (backupStateStore.isActive()) {
                        activeBackupWorkId = null
                        operation = OperationState(
                            "מגבה ל‑Google Drive",
                            "התהליך הופסק זמנית — ממשיך אוטומטית",
                            null,
                            cancellable = true
                        )
                        BackupRecoveryScheduler.kick(this)
                    } else {
                        finishDriveWorkUi(info.id,"הגיבוי נעצר בצורה בטוחה")
                    }
                }
'''
s = s[:failed_start] + terminal_replacement + s[gmail_after:]

old_cancel = '''private fun cancelCurrentOperation(){ val id=activeBackupWorkId;if(id!=null&&operation?.cancellable==true){operation=operation?.copy(message="עוצר בצורה בטוחה…");workManager.cancelWorkById(id);return};if(operation?.cancellable==true){cancellingBackup=true;operation=operation?.copy(message="עוצר בצורה בטוחה…")} }'''
new_cancel = '''private fun cancelCurrentOperation(){
        val id=activeBackupWorkId
        if(id!=null&&operation?.cancellable==true){
            backupStateStore.cancel()
            operation=operation?.copy(message="עוצר בצורה בטוחה…")
            workManager.cancelUniqueWork(DriveBackupWorker.UNIQUE_WORK_NAME)
            return
        }
        if(operation?.cancellable==true){
            backupStateStore.cancel()
            cancellingBackup=true
            operation=operation?.copy(message="עוצר בצורה בטוחה…")
        }
    }'''
if old_cancel not in s:
    raise SystemExit("cancelCurrentOperation marker not found")
s = s.replace(old_cancel, new_cancel)

resume_marker = '''        refreshDriveState()
    }

    override fun onDestroy()'''
resume_replacement = '''        refreshDriveState()
        if (backupStateStore.isActive()) {
            BackupRecoveryScheduler.kick(this)
        }
    }

    override fun onDestroy()'''
if resume_marker not in s:
    raise SystemExit("onResume marker not found")
s = s.replace(resume_marker, resume_replacement, 1)

main.write_text(s)
print("PhoneMind v0.3.2 always-on backup patch applied")
