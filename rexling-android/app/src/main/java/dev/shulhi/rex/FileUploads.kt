package dev.shulhi.rex

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

internal data class UploadTarget(val server: String,val endpoint: String,val session: String,val block: String,
    val navigation: Long,val serverSwitch: Long,val program: ProgramKind,val label: String)
internal enum class UploadPhase { PREPARING, UPLOADING, FINISHING, SUCCESS, FAILED }
/** `names` lists the batch in typing order; `current` is the file being sent. */
internal data class UploadStatus(val target: UploadTarget,val phase: UploadPhase,val names: List<String>,
    val progress: Float=0f,val current: Int=0,val error: String="",val retrySafe: Boolean=true) {
    val count: Int get()=names.size
}
internal data class StagedFile(val file: File,val name: String)
internal const val MAX_UPLOAD_BYTES=512L*1024*1024
internal const val MAX_UPLOAD_FILES=32
internal val imageMimeTypes=arrayOf("image/png","image/jpeg","image/webp","image/gif")

internal fun imageExtension(header: ByteArray): String? = when {
    header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))->"png"
    header.size>=3 && header[0]==0xff.toByte() && header[1]==0xd8.toByte() && header[2]==0xff.toByte()->"jpg"
    header.size>=6 && String(header,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")->"gif"
    header.size>=12 && String(header,0,4,Charsets.US_ASCII)=="RIFF" && String(header,8,4,Charsets.US_ASCII)=="WEBP"->"webp"
    else->null
}

internal object UploadNames {
    private const val MAX_CHARS=120

    /** Rex keeps only the last path component; images without a usable name get one from their bytes. */
    fun of(displayName: String?,header: ByteArray): String {
        val image=imageExtension(header)
        val base=displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filterNot {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}.trim()
            .takeUnless {it.isEmpty() || it.all {c->c=='.'}}
        val named=when {
            base==null->if(image!=null) "image.$image" else "file"
            image!=null && !base.contains('.')->"$base.$image"
            else->base
        }
        if(named.length<=MAX_CHARS) return named
        val extension=named.substringAfterLast('.',"").takeIf {it.length in 1..12}
        return if(extension==null) named.take(MAX_CHARS) else named.take(MAX_CHARS-extension.length-1).trimEnd()+"."+extension
    }
}

internal class FileUploads(private val context: Context,private val scope: CoroutineScope,
    private val native: RexUploadBackend=RexFileUpload()) {
    var state by mutableStateOf<UploadStatus?>(null);private set
    var notice by mutableStateOf("")
    private var job: Job?=null
    private var staged: List<StagedFile>?=null
    val retryable: Boolean get()=staged!=null && state?.retrySafe==true
    private var operation=0L
    private val directory=File(context.cacheDir,"uploads")
    private var preparing: List<File> = emptyList()

    fun start(uris: List<Uri>,target: UploadTarget,onCopied: ()->Unit={}): Boolean {
        if(state?.phase==UploadPhase.SUCCESS) clear()
        val refusal=when {
            state!=null->"Finish or cancel the current upload first."
            uris.isEmpty()->"Choose a file to upload."
            uris.size>MAX_UPLOAD_FILES->"Choose up to $MAX_UPLOAD_FILES files at a time."
            uris.any {it.scheme!="content"}->"Choose files from your clipboard or the file picker."
            else->null
        }
        if(refusal!=null) {onCopied();notice=refusal;return false}
        val released=java.util.concurrent.atomic.AtomicBoolean(false)
        val release={if(released.compareAndSet(false,true)) onCopied()}
        val files=uris.map {File(directory,UUID.randomUUID().toString())};preparing=files
        state=UploadStatus(target,UploadPhase.PREPARING,uris.map {displayName(it) ?: "file"})
        val id=++operation
        job=scope.launch {
            try {
                staged=withContext(Dispatchers.IO) {stage(uris,files,release)}
                preparing=emptyList()
                if(id==operation) {state=state?.copy(names=staged!!.map {it.name});upload(id)}
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {if(id==operation) state=state?.copy(phase=UploadPhase.FAILED,error=error.message ?: "Could not prepare this file")}
        }
        job?.invokeOnCompletion {release()}
        return true
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
            if(cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private suspend fun stage(uris: List<Uri>,files: List<File>,onCopied: ()->Unit): List<StagedFile> {
        try {
            directory.mkdirs()
            directory.listFiles()?.filter {it.lastModified()<System.currentTimeMillis()-24*60*60*1000L && it !in files}?.forEach {it.delete()}
            return uris.zip(files).map {(uri,file)->
                val input=context.contentResolver.openInputStream(uri) ?: error("This file is no longer available")
                input.use {source->file.outputStream().use {sink->
                    val buffer=ByteArray(65536);var total=0L
                    while(true) {currentCoroutineContext().ensureActive();val n=source.read(buffer);if(n<0) break;total+=n
                        require(total<=MAX_UPLOAD_BYTES) {"Choose files smaller than 512 MiB"};sink.write(buffer,0,n)}
                    require(total>0) {"${displayName(uri) ?: "This file"} is empty"}
                }}
                val header=file.inputStream().use {stream->val bytes=ByteArray(12);var n=0;while(n<12) {val count=stream.read(bytes,n,12-n);if(count<0) break;n+=count};bytes.copyOf(n)}
                StagedFile(file,UploadNames.of(displayName(uri),header))
            }
        } catch(error: Throwable) {files.forEach {it.delete()};throw error}
        finally {onCopied()}
    }

    private suspend fun upload(id: Long) {
        val current=state ?: return
        val files=staged ?: error("Choose the files again to retry")
        state=current.copy(phase=UploadPhase.PREPARING,error="",progress=0f,current=0)
        val result=native.upload(current.target,files) {phase,progress,file->
            if(id==operation) state=state?.copy(phase=when(phase) {"preparing"->UploadPhase.PREPARING;"finishing"->UploadPhase.FINISHING;else->UploadPhase.UPLOADING},
                progress=progress,current=file.coerceIn(0,files.lastIndex))
        }
        if(id!=operation) return
        when(result.phase) {
            "done"->{
                state=state?.copy(phase=UploadPhase.SUCCESS,progress=1f,current=files.lastIndex)
                delay(750)
                if(id==operation) {job=null;clear()}
                return
            }
            "uncertain"->{state=state?.copy(phase=UploadPhase.FAILED,error=result.error,retrySafe=false);return}
            "failed"->{state=state?.copy(phase=UploadPhase.FAILED,error=result.error);return}
            "canceled"->return
            "unsupported"->{state=state?.copy(phase=UploadPhase.FAILED,error="Update the Rex server to enable uploads (API revision 11 or newer).");return}
            else->error("Unexpected upload state")
        }
    }
    fun retry() {
        if(state?.phase!=UploadPhase.FAILED || !retryable) return
        if(staged==null) {notice="These files could not be prepared. Cancel and choose them again.";return}
        val id=++operation
        job=scope.launch {try {upload(id)} catch(error: CancellationException) {throw error} catch(error: Exception) {if(id==operation) state=state?.copy(phase=UploadPhase.FAILED,error=error.message ?: "Upload failed")}}
    }
    fun cancel() {
        if(state?.phase==UploadPhase.FINISHING) notice="Upload stopped while finishing. Check the original pane before trying again."
        clear()
    }
    private fun clear() {
        operation++;job?.cancel();job=null
        val files=(preparing+staged.orEmpty().map {it.file}).distinct()
        preparing=emptyList();staged=null;state=null
        if(files.isNotEmpty()) scope.launch(Dispatchers.IO+NonCancellable) {files.forEach {it.delete()}}
    }
    fun forget(serverId: String) {if(state?.target?.server==serverId) cancel()}
    fun close()=cancel()
}

/** A copied file or image becomes an upload; anything textual pastes as text. */
internal fun pasteTerminalClipboard(context: Context,onFile: (Uri)->Boolean,onText: (String)->Unit): Boolean {
    val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip ?: return false
    if(clip.itemCount==0) return false
    val item=clip.getItemAt(0);val uri=item.uri
    val type=uri?.let {runCatching {context.contentResolver.getType(it)}.getOrNull()} ?: clip.description.takeIf {it.mimeTypeCount>0}?.getMimeType(0)
    return if(uri!=null && uri.scheme=="content" && type!=null && !type.startsWith("text/")) onFile(uri)
        else {val text=item.coerceToText(context)?.toString().orEmpty();if(text.isEmpty()) false else {onText(text);true}}
}
