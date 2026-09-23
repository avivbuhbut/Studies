from pathlib import Path

root = Path('PhoneMindAndroid/app/src/main/java/com/aviv/phonemind')

worker = root / 'DriveBackupWorker.kt'
s = worker.read_text()
s = s.replace('import android.app.ServiceInfo', 'import android.content.pm.ServiceInfo')
worker.write_text(s)

repo = root / 'DriveBackupRepository.kt'
s = repo.read_text()
s = s.replace(
    'private fun copyWithHash(file: File, dest: DocumentFile, item: FileItem, shouldCancel: () -> Boolean): String? = try {',
    'private fun copyWithHash(file: File, dest: DocumentFile, item: FileItem, shouldCancel: () -> Boolean): String? {\n        return try {'
)
s = s.replace(
    '} catch (_: BackupCancelledException) { null } catch (_: Throwable) { null }\n\n    private fun verifyDestination',
    '} catch (_: BackupCancelledException) { null } catch (_: Throwable) { null }\n    }\n\n    private fun verifyDestination'
)
s = s.replace(
    'private fun sha256Document(doc: DocumentFile, shouldCancel: () -> Boolean): String? = try {',
    'private fun sha256Document(doc: DocumentFile, shouldCancel: () -> Boolean): String? {\n        return try {'
)
s = s.replace(
    '} catch (_: Throwable) { null }\n\n    private fun sha256Local(file: File, shouldCancel: () -> Boolean): String? = try {',
    '} catch (_: Throwable) { null }\n    }\n\n    private fun sha256Local(file: File, shouldCancel: () -> Boolean): String? {\n        return try {'
)
marker = 'private fun sha256Local'
pos = s.find(marker)
catchpos = s.find('    } catch (_: Throwable) { null }', pos)
if catchpos >= 0:
    end = catchpos + len('    } catch (_: Throwable) { null }')
    if not s[end:].startswith('\n    }'):
        s = s[:end] + '\n    }' + s[end:]
repo.write_text(s)
print('v0.3.1 compile hotfix applied')
