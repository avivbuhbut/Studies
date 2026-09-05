from pathlib import Path
import re, sys
root = Path(sys.argv[1] if len(sys.argv)>1 else 'PhoneMindAndroid')
app = root/'app'
src = app/'src/main/java/com/aviv/phonemind'

gradle = app/'build.gradle.kts'
s = gradle.read_text()
s = s.replace('versionCode = 3', 'versionCode = 4')
s = s.replace('versionName = "0.3.0"', 'versionName = "0.3.1"')
if 'androidx.work:work-runtime-ktx' not in s:
    s = s.replace('implementation("androidx.documentfile:documentfile:1.1.0")',
                  'implementation("androidx.documentfile:documentfile:1.1.0")\n    implementation("androidx.work:work-runtime-ktx:2.10.0")\n    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")')
gradle.write_text(s)

manifest = app/'src/main/AndroidManifest.xml'
s = manifest.read_text()
if 'xmlns:tools=' not in s:
    s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">')
if 'android.permission.FOREGROUND_SERVICE"' not in s:
    s = s.replace('    <uses-permission android:name="android.permission.INTERNET" />',
                  '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />')
service = '''\n        <service\n            android:name="androidx.work.impl.foreground.SystemForegroundService"\n            android:foregroundServiceType="dataSync"\n            tools:node="merge" />\n'''
if 'SystemForegroundService' not in s:
    s = s.replace('        <activity\n            android:name=".MainActivity"', service + '        <activity\n            android:name=".MainActivity"')
manifest.write_text(s)

(src/'DriveBackupRepository.kt').write_text(r'''package com.aviv.phonemind

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

class DriveBackupRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("drive_backup", Context.MODE_PRIVATE)

    fun savedTreeUri(): Uri? = prefs.getString("tree_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
    fun hasDestination(): Boolean = savedTreeUri() != null

    fun destinationName(): String? {
        val uri = savedTreeUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    }

    fun saveTreeUri(uri: Uri) { prefs.edit().putString("tree_uri", uri.toString()).apply() }

    fun clearDestination() {
        savedTreeUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove("tree_uri").apply()
    }

    fun backup(
        sourceItems: List<FileItem>,
        categories: Set<String>,
        deleteAfterUpload: Boolean,
        progress: (Int, String) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): BackupResult {
        val treeUri = savedTreeUri() ?: throw IllegalStateException("בחר קודם תיקיית Google Drive")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("לא ניתן לפתוח את תיקיית Google Drive")
        if (!root.canWrite()) throw IllegalStateException("הגישה לתיקיית Google Drive פגה. בחר אותה מחדש.")

        val selected = sourceItems.filter { categories.contains(it.category) && File(it.path).isFile }
        if (selected.isEmpty()) throw IllegalStateException("לא נמצאו קבצים בקטגוריות שבחרת")

        val backupRoot = root.findFile("PhoneMind Backup")?.takeIf { it.isDirectory }
            ?: root.createDirectory("PhoneMind Backup")
            ?: throw IllegalStateException("לא הצלחתי ליצור את תיקיית PhoneMind Backup")

        val externalRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val folderCache = mutableMapOf<String, DocumentFile>()
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var deleted = 0
        var uploadedBytes = 0L
        var deletedBytes = 0L
        var cancelled = false
        val deletedPaths = ArrayList<String>()
        val totalBytes = selected.sumOf { it.size }.coerceAtLeast(1L)
        var processedBytes = 0L

        loop@ for ((index, item) in selected.withIndex()) {
            if (shouldCancel()) { cancelled = true; break }
            val file = File(item.path)
            if (!localMetadataMatches(file, item)) {
                failed++; processedBytes += item.size
                progress(percent(processedBytes, totalBytes), "דילגתי על קובץ שהשתנה: ${item.name}")
                continue
            }

            val categoryFolder = getOrCreateFolder(backupRoot, item.category)
            if (categoryFolder == null) {
                failed++; processedBytes += item.size
                progress(percent(processedBytes, totalBytes), "לא הצלחתי ליצור תיקייה עבור ${item.category}")
                continue
            }
            val rel = item.path.removePrefix(externalRoot).trimStart('/')
            val relDir = rel.substringBeforeLast('/', "")
            val targetDir = getOrCreateNested(categoryFolder, relDir, folderCache, item.category)
            if (targetDir == null) {
                failed++; processedBytes += item.size
                progress(percent(processedBytes, totalBytes), "לא הצלחתי ליצור נתיב עבור ${item.name}")
                continue
            }

            val existing = targetDir.findFile(item.name)
            if (existing != null && existing.isFile) {
                val localHash = sha256Local(file, shouldCancel)
                if (localHash == null) {
                    if (shouldCancel()) { cancelled = true; break@loop }
                } else if (localMetadataMatches(file, item) && verifyDestination(existing, item.size, localHash, shouldCancel)) {
                    skipped++
                    if (deleteAfterUpload && localMetadataMatches(file, item) && sha256Local(file, shouldCancel) == localHash) {
                        if (deleteLocal(file, item)) {
                            deleted++; deletedBytes += item.size; deletedPaths += item.path
                        }
                    }
                    processedBytes += item.size
                    progress(percent(processedBytes, totalBytes), "כבר מגובה ומאומת: ${item.name}")
                    continue
                }
                if (!safeDeleteDocument(existing)) {
                    failed++; processedBytes += item.size
                    progress(percent(processedBytes, totalBytes), "לא ניתן להחליף עותק חלקי ב‑Drive: ${item.name}")
                    continue
                }
            } else if (existing != null) {
                failed++; processedBytes += item.size
                progress(percent(processedBytes, totalBytes), "קיים ב‑Drive פריט עם אותו שם: ${item.name}")
                continue
            }

            var transferred = false
            var transferHash: String? = null
            var finalDest: DocumentFile? = null
            for (attempt in 0 until 3) {
                if (shouldCancel()) { cancelled = true; break@loop }
                targetDir.findFile(item.name)?.let { safeDeleteDocument(it) }
                val dest = targetDir.createFile(mimeType(item.name), item.name)
                if (dest == null) { if (attempt < 2) sleepBackoff(attempt); continue }
                finalDest = dest
                val copied = copyWithHash(file, dest, item, shouldCancel)
                if (copied == null) {
                    safeDeleteDocument(dest)
                    if (shouldCancel()) { cancelled = true; break@loop }
                    if (attempt < 2) sleepBackoff(attempt)
                    continue
                }
                transferHash = copied
                if (!localMetadataMatches(file, item)) {
                    safeDeleteDocument(dest); transferHash = null; break
                }
                if (verifyDestination(dest, item.size, copied, shouldCancel)) { transferred = true; break }
                safeDeleteDocument(dest); transferHash = null
                if (shouldCancel()) { cancelled = true; break@loop }
                if (attempt < 2) sleepBackoff(attempt)
            }

            if (cancelled) break
            if (transferred && finalDest != null && transferHash != null) {
                uploaded++; uploadedBytes += item.size
                if (deleteAfterUpload && localMetadataMatches(file, item)) {
                    val finalLocalHash = sha256Local(file, shouldCancel)
                    if (finalLocalHash == transferHash && verifyDestination(finalDest, item.size, transferHash, shouldCancel)) {
                        if (deleteLocal(file, item)) {
                            deleted++; deletedBytes += item.size; deletedPaths += item.path
                        }
                    }
                }
            } else failed++

            processedBytes += item.size
            if (index % 2 == 0 || index == selected.lastIndex) {
                progress(percent(processedBytes, totalBytes), "${uploaded + skipped}/${selected.size} קבצים · ${formatBytes(uploadedBytes)}")
            }
        }

        if (deletedPaths.isNotEmpty()) MediaScannerConnection.scanFile(context, deletedPaths.toTypedArray(), null, null)
        progress(if (cancelled) percent(processedBytes, totalBytes) else 100, if (cancelled) "הגיבוי נעצר" else "הגיבוי הושלם")
        return BackupResult(selected.size, selected.sumOf { it.size }, uploaded, uploadedBytes, skipped, failed, deleted, deletedBytes, cancelled, deletedPaths.toList())
    }

    private fun copyWithHash(file: File, dest: DocumentFile, item: FileItem, shouldCancel: () -> Boolean): String? = try {
        if (!localMetadataMatches(file, item)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openOutputStream(dest.uri, "w") ?: return null
        stream.use { output ->
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    if (shouldCancel()) throw BackupCancelledException()
                    val read = input.read(buffer); if (read <= 0) break
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                }
                output.flush()
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: BackupCancelledException) { null } catch (_: Throwable) { null }

    private fun verifyDestination(doc: DocumentFile, expectedSize: Long, expectedHash: String, shouldCancel: () -> Boolean): Boolean {
        repeat(4) { attempt ->
            if (shouldCancel()) return false
            if (verifiedLength(doc) == expectedSize) {
                val hash = sha256Document(doc, shouldCancel)
                if (hash != null && hash == expectedHash) return true
            }
            if (attempt < 3) try { Thread.sleep(longArrayOf(300L, 700L, 1400L)[attempt]) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun sha256Document(doc: DocumentFile, shouldCancel: () -> Boolean): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(doc.uri) ?: return null
        input.use {
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (shouldCancel()) return null
                val read = it.read(buffer); if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { null }

    private fun sha256Local(file: File, shouldCancel: () -> Boolean): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (shouldCancel()) return null
                val read = input.read(buffer); if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { null }

    private fun verifiedLength(file: DocumentFile): Long {
        val direct = runCatching { file.length() }.getOrDefault(-1L)
        if (direct >= 0) return direct
        return runCatching { context.contentResolver.openAssetFileDescriptor(file.uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
    }

    private fun localMetadataMatches(file: File, item: FileItem): Boolean = try {
        file.exists() && file.isFile && file.length() == item.size && (item.modified <= 0L || file.lastModified() == item.modified)
    } catch (_: Throwable) { false }
    private fun deleteLocal(file: File, item: FileItem): Boolean = try { localMetadataMatches(file, item) && file.delete() } catch (_: Throwable) { false }
    private fun safeDeleteDocument(doc: DocumentFile): Boolean = runCatching { !doc.exists() || doc.delete() }.getOrDefault(false)
    private fun sleepBackoff(attempt: Int) { try { Thread.sleep(longArrayOf(350L, 900L, 1800L)[attempt.coerceIn(0,2)]) } catch (_: InterruptedException) {} }

    private fun getOrCreateNested(categoryRoot: DocumentFile, relativeDir: String, cache: MutableMap<String, DocumentFile>, category: String): DocumentFile? {
        if (relativeDir.isBlank()) return categoryRoot
        var current = categoryRoot; var built = ""
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { raw ->
            val name = sanitize(raw); built = if (built.isBlank()) name else "$built/$name"; val key = "$category/$built"
            current = cache[key] ?: (current.findFile(name)?.takeIf { it.isDirectory } ?: current.createDirectory(name))?.also { cache[key] = it } ?: return null
        }
        return current
    }
    private fun getOrCreateFolder(parent: DocumentFile, rawName: String): DocumentFile? { val name=sanitize(rawName); return parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name) }
    private fun sanitize(value: String): String = value.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "ללא שם" }
    private fun mimeType(name: String): String { val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT); return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }
    private fun percent(done: Long, total: Long): Int = ((done.toDouble()/total.toDouble())*100.0).toInt().coerceIn(1,100)
    private fun formatBytes(bytes: Long): String { val units=arrayOf("B","KB","MB","GB","TB"); var value=bytes.toDouble(); var i=0; while(value>=1024&&i<units.lastIndex){value/=1024.0;i++}; return if(i==0)"$bytes B" else String.format(Locale.US,"%.1f %s",value,units[i]) }
    private class BackupCancelledException : RuntimeException()
}
''')

(src/'DriveBackupWorker.kt').write_text(r'''package com.aviv.phonemind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

class DriveBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(0, "מכין את הגיבוי…"))
        val categories = inputData.getStringArray(KEY_CATEGORIES)?.toSet().orEmpty()
        val deleteAfter = inputData.getBoolean(KEY_DELETE_AFTER, false)
        if (categories.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "לא נבחרו קטגוריות לגיבוי"))
        val db = IndexDatabase(applicationContext)
        try {
            val storageRepo = StorageRepository(applicationContext, db)
            val driveRepo = DriveBackupRepository(applicationContext)
            val cached = storageRepo.ensureFilesLoaded()
            val missing = cached.asSequence().filter { !File(it.path).isFile }.map { it.path }.toList()
            if (missing.isNotEmpty()) storageRepo.refreshAfterExternalDeletes(missing)
            val result = driveRepo.backup(
                storageRepo.ensureFilesLoaded(), categories, deleteAfter,
                progress = { pct, msg ->
                    setProgressAsync(workDataOf(KEY_PROGRESS to pct, KEY_MESSAGE to msg))
                    setForegroundAsync(foregroundInfo(pct, msg))
                }, shouldCancel = { isStopped }
            )
            if (result.deletedPaths.isNotEmpty()) storageRepo.refreshAfterExternalDeletes(result.deletedPaths)
            if (result.failedFiles > 0 && !result.cancelled && runAttemptCount < 2) return Result.retry()
            return Result.success(workDataOf(
                KEY_SELECTED_FILES to result.selectedFiles, KEY_SELECTED_BYTES to result.selectedBytes,
                KEY_UPLOADED_FILES to result.uploadedFiles, KEY_UPLOADED_BYTES to result.uploadedBytes,
                KEY_SKIPPED_FILES to result.skippedFiles, KEY_FAILED_FILES to result.failedFiles,
                KEY_DELETED_FILES to result.deletedFiles, KEY_DELETED_BYTES to result.deletedBytes,
                KEY_CANCELLED to result.cancelled
            ))
        } catch (t: Throwable) {
            val message=t.message.orEmpty()
            val permanent=message.contains("בחר",true)||message.contains("פגה",true)||message.contains("לא נמצאו קבצים",true)
            return if(!permanent&&runAttemptCount<4&&!isStopped) Result.retry() else Result.failure(workDataOf(KEY_ERROR to message.ifBlank{"הגיבוי נכשל. נסה שוב."}))
        } finally { runCatching { db.close() } }
    }

    private fun foregroundInfo(progress: Int, message: String): ForegroundInfo {
        createChannel()
        val pendingIntent=PendingIntent.getActivity(applicationContext,31,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(applicationContext,CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("PhoneMind · Google Drive").setContentText(message).setContentIntent(pendingIntent).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).setProgress(100,progress.coerceIn(0,100),progress<=0).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID,n)
    }
    private fun createChannel(){ if(Build.VERSION.SDK_INT>=26){ val m=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; if(m.getNotificationChannel(CHANNEL_ID)==null)m.createNotificationChannel(NotificationChannel(CHANNEL_ID,"PhoneMind backups",NotificationManager.IMPORTANCE_LOW).apply{description="Google Drive backup progress"}) } }

    companion object {
        const val UNIQUE_WORK_NAME="phonemind_drive_backup"; const val KEY_CATEGORIES="categories"; const val KEY_DELETE_AFTER="delete_after"; const val KEY_PROGRESS="progress"; const val KEY_MESSAGE="message"; const val KEY_ERROR="error"; const val KEY_SELECTED_FILES="selected_files"; const val KEY_SELECTED_BYTES="selected_bytes"; const val KEY_UPLOADED_FILES="uploaded_files"; const val KEY_UPLOADED_BYTES="uploaded_bytes"; const val KEY_SKIPPED_FILES="skipped_files"; const val KEY_FAILED_FILES="failed_files"; const val KEY_DELETED_FILES="deleted_files"; const val KEY_DELETED_BYTES="deleted_bytes"; const val KEY_CANCELLED="cancelled"; private const val CHANNEL_ID="phonemind_drive_backup"; private const val NOTIFICATION_ID=31031
        fun request(categories:Set<String>,deleteAfter:Boolean):OneTimeWorkRequest=OneTimeWorkRequest.Builder(DriveBackupWorker::class.java).setInputData(workDataOf(KEY_CATEGORIES to categories.toTypedArray(),KEY_DELETE_AFTER to deleteAfter)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(UNIQUE_WORK_NAME).build()
    }
}
''')

main=src/'MainActivity.kt'; s=main.read_text()
imports='''import androidx.work.ExistingWorkPolicy\nimport androidx.work.WorkInfo\nimport androidx.work.WorkManager\nimport java.util.UUID\n'''
if 'import androidx.work.WorkManager' not in s: s=s.replace('import androidx.lifecycle.lifecycleScope\n','import androidx.lifecycle.lifecycleScope\n'+imports)
if 'private val driveWorkPrefs' not in s: s=s.replace('    private lateinit var driveRepo: DriveBackupRepository\n','    private lateinit var driveRepo: DriveBackupRepository\n    private val driveWorkPrefs by lazy { getSharedPreferences("drive_work", MODE_PRIVATE) }\n    private val workManager by lazy { WorkManager.getInstance(applicationContext) }\n    private var activeBackupWorkId: UUID? = null\n')
needle='        gmailPreviouslyAuthorized = gmailRepo.hasPreviousAuthorization()\n        refreshDriveState()\n\n        setContent'
if needle in s: s=s.replace(needle,'        gmailPreviouslyAuthorized = gmailRepo.hasPreviousAuthorization()\n        refreshDriveState()\n        restoreDriveBackupWork()\n\n        setContent')
new=r'''    private fun startDriveBackup(deleteAfter: Boolean) {
        if (operation != null) return
        cancellingBackup = false
        val categories = selectedCategories.toSet()
        if (categories.isEmpty()) return
        operation = OperationState("מגבה ל‑Google Drive", "מכין את הקבצים…", 0f, cancellable = true)
        lifecycleScope.launch {
            try {
                val existing = withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(DriveBackupWorker.UNIQUE_WORK_NAME).get().firstOrNull { !it.state.isFinished } }
                if (existing != null) { observeDriveBackupWork(existing.id); return@launch }
                val request=DriveBackupWorker.request(categories,deleteAfter)
                driveWorkPrefs.edit().putString("active_work_id",request.id.toString()).putBoolean("active_delete_after",deleteAfter).apply()
                workManager.enqueueUniqueWork(DriveBackupWorker.UNIQUE_WORK_NAME,ExistingWorkPolicy.KEEP,request)
                observeDriveBackupWork(request.id)
            } catch(t:Throwable){ Log.e("PhoneMindDrive","Unable to schedule backup",t); operation=null; showNotice("לא הצלחתי להתחיל את הגיבוי. נסה שוב.") }
        }
    }

    private fun restoreDriveBackupWork(){ val raw=driveWorkPrefs.getString("active_work_id",null)?:return; val id=runCatching{UUID.fromString(raw)}.getOrNull()?:run{driveWorkPrefs.edit().remove("active_work_id").apply();return}; observeDriveBackupWork(id) }

    private fun observeDriveBackupWork(id:UUID){
        if(activeBackupWorkId==id)return
        activeBackupWorkId=id; driveWorkPrefs.edit().putString("active_work_id",id.toString()).apply()
        workManager.getWorkInfoByIdLiveData(id).observe(this){info->
            if(info==null)return@observe
            when(info.state){
                WorkInfo.State.ENQUEUED,WorkInfo.State.BLOCKED->{ val d=driveWorkPrefs.getBoolean("active_delete_after",false); operation=OperationState(if(d)"מגבה ומפנה מקום" else "מגבה ל‑Google Drive","ממתין לחיבור יציב…",null,cancellable=true) }
                WorkInfo.State.RUNNING->{ val pct=info.progress.getInt(DriveBackupWorker.KEY_PROGRESS,0); val msg=info.progress.getString(DriveBackupWorker.KEY_MESSAGE)?:"ממשיך את הגיבוי…"; val d=driveWorkPrefs.getBoolean("active_delete_after",false); operation=OperationState(if(d)"מגבה ומפנה מקום" else "מגבה ל‑Google Drive",msg,if(pct>0)pct/100f else null,cancellable=true) }
                WorkInfo.State.SUCCEEDED->{ val d=info.outputData; val r=BackupResult(d.getInt(DriveBackupWorker.KEY_SELECTED_FILES,0),d.getLong(DriveBackupWorker.KEY_SELECTED_BYTES,0),d.getInt(DriveBackupWorker.KEY_UPLOADED_FILES,0),d.getLong(DriveBackupWorker.KEY_UPLOADED_BYTES,0),d.getInt(DriveBackupWorker.KEY_SKIPPED_FILES,0),d.getInt(DriveBackupWorker.KEY_FAILED_FILES,0),d.getInt(DriveBackupWorker.KEY_DELETED_FILES,0),d.getLong(DriveBackupWorker.KEY_DELETED_BYTES,0),d.getBoolean(DriveBackupWorker.KEY_CANCELLED,false),emptyList()); lastBackup=r; reloadStorageStateAfterWorker(); if(!r.cancelled&&r.failedFiles==0)selectedCategories=emptySet(); finishDriveWorkUi(info.id,when{r.cancelled->"הגיבוי נעצר בצורה בטוחה. מה שכבר אומת נשמר.";r.failedFiles>0->"הגיבוי הסתיים עם ${r.failedFiles} קבצים שלא הועלו. הקבצים נשארו בטלפון.";else->"הגיבוי הושלם · ${formatBytes(r.uploadedBytes)}"}) }
                WorkInfo.State.FAILED->{ val e=info.outputData.getString(DriveBackupWorker.KEY_ERROR).orEmpty(); finishDriveWorkUi(info.id,e.ifBlank{"הגיבוי נכשל. הקבצים שלא אומתו נשארו בטלפון."}) }
                WorkInfo.State.CANCELLED->finishDriveWorkUi(info.id,"הגיבוי נעצר בצורה בטוחה")
            }
        }
    }
    private fun reloadStorageStateAfterWorker(){ storageRepo=StorageRepository(this,db); summary=storageRepo.latestSummary; duplicates=emptyList(); hydrateStorageDetails() }
    private fun finishDriveWorkUi(id:UUID,message:String){ operation=null;cancellingBackup=false;if(activeBackupWorkId==id)activeBackupWorkId=null;driveWorkPrefs.edit().remove("active_work_id").remove("active_delete_after").apply();val marker=id.toString();if(driveWorkPrefs.getString("last_handled_work_id",null)!=marker){driveWorkPrefs.edit().putString("last_handled_work_id",marker).apply();showNotice(message)} }
    private fun cancelCurrentOperation(){ val id=activeBackupWorkId;if(id!=null&&operation?.cancellable==true){operation=operation?.copy(message="עוצר בצורה בטוחה…");workManager.cancelWorkById(id);return};if(operation?.cancellable==true){cancellingBackup=true;operation=operation?.copy(message="עוצר בצורה בטוחה…")} }
'''
pat=re.compile(r'    private fun startDriveBackup\(deleteAfter: Boolean\) \{.*?\n    private fun gmailScopes\(\): List<Scope> =',re.S)
m=pat.search(s)
if not m: raise SystemExit('Could not locate Drive block')
s=s[:m.start()]+new+'\n    private fun gmailScopes(): List<Scope> ='+s[m.end():]
main.write_text(s)
print('PhoneMind v0.3.1 reliability patch applied')
