package dev.shulhi.rex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RexUploadResult(val phase: String,val error: String)
internal fun interface RexUploadBackend {
    /** Reports (phase, fraction of all bytes confirmed, index of the file being sent). */
    suspend fun upload(target: UploadTarget,files: List<StagedFile>,onProgress: (String,Float,Int)->Unit): RexUploadResult
}

/** Several files travel as one Rex batch, so their paths are typed together once the last one lands. */
internal class RexFileUpload: RexUploadBackend {
    override suspend fun upload(target: UploadTarget,files: List<StagedFile>,onProgress: (String,Float,Int)->Unit): RexUploadResult {
        val request=JSONObject().put("endpoint",target.endpoint).put("session",target.session).put("block",target.block)
            .put("files",JSONArray(files.map {JSONObject().put("path",it.file.absolutePath).put("name",it.name).put("ref","upload:${UUID.randomUUID()}")}))
        if(files.size>1) request.put("batch","batch:${UUID.randomUUID()}")
        val handle=NativeBridge.uploadCreate(request.toString())
        check(handle!=0L) {"Could not start upload"}
        try {
            while(true) {
                currentCoroutineContext().ensureActive()
                val status=JSONObject(NativeBridge.uploadPoll(handle));val phase=status.getString("phase")
                if(phase in listOf("done","unsupported","failed","uncertain","canceled")) return RexUploadResult(phase,status.optString("error"))
                val total=status.optLong("total");val sent=status.optLong("sent")
                onProgress(phase,if(total>0) (sent.toDouble()/total).toFloat().coerceIn(0f,1f) else 0f,status.optInt("file"))
                delay(40)
            }
        } catch(error: CancellationException) {throw error}
        catch(error: Exception) {return RexUploadResult("uncertain","Could not confirm the upload. Check the original pane before trying again.")}
        finally {NativeBridge.uploadDestroy(handle)}
    }
}
