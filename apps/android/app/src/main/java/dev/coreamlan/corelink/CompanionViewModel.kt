package dev.coreamlan.corelink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.json.JSONObject
class CompanionViewModel: ViewModel() { var connected by mutableStateOf(false); var busy by mutableStateOf(false); var message by mutableStateOf(""); var error by mutableStateOf(false); private var client:CoreLinkClient?=null
 fun pair(host:String,code:String){busy=true; message=""; val c=CoreLinkClient(host.trim()); val result=c.pair(code,android.os.Build.MODEL); busy=false; result.onSuccess{client=c;connected=true;message="Approved by desktop"}.onFailure{error=true;message=it.message?:"Pairing failed"}}
 fun input(value:JSONObject){client?.input(value)?.onFailure{error=true;message=it.message?:"Input failed"}}
 fun disconnect(){client?.disconnect();client=null;connected=false;message="Disconnected"}
}
