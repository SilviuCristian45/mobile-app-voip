package com.example.android

import android.Manifest
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.nfc.Tag
import android.media.AudioTrack
import android.media.AudioManager

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 123
    }

    private var isRecording = false
    private lateinit var recordButton: Button
    private var recordingJob: Job? = null
    private var receivingJob: Job? = null
    private val tag = "voip";

    private val SERVER_IP = ""
    private val SERVER_PORT = 41234
    private val BUFFER_SIZE = 2048

    // În clasa ta (e.g., în ViewModel sau unde rulezi corutinele)
    private var sharedSocket: DatagramSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        sharedSocket = DatagramSocket(this.SERVER_PORT).apply {
            soTimeout = 0 // blocant
        }


        receivingJob = CoroutineScope(Dispatchers.IO).launch {
            val localIp = getLocalIpAddress()
            val buffer = ByteArray(2048)

            val packet = DatagramPacket(buffer, buffer.size)

            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                16000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer.size,
                AudioTrack.MODE_STREAM
            )

            audioTrack.play()

            while (isActive) {
                Log.d(this.tag, "receive audio job")
                sharedSocket?.receive(packet)
                val senderIp = packet.address?.hostAddress
                Log.d(this.tag, "Primit pachet de la $senderIp cu lungimea ${packet.length}")
                audioTrack.write(packet.data, 0, packet.length)
            }

            audioTrack.stop()
            audioTrack.release()
        }


        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_AUDIO_PERMISSION
        )
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(this.tag, "Eroare la obținerea IP-ului local", ex)
        }
        return null
    }


    private fun startRecording() {
        isRecording = true
        recordButton.setBackgroundColor(Color.RED)
        recordButton.text = "Stop"

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            recordAndSendAudioUDP()
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.setBackgroundColor(Color.GRAY)
        recordButton.text = "Record"
        recordingJob?.cancel()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun recordAndSendAudioUDP() {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        val buffer = ByteArray(minBufferSize)
        val address = InetAddress.getByName("192.168.216.98") // <- IP server
        val port = 41234

        recorder.startRecording()

        while (isRecording && isActive) {
            val read = recorder.read(buffer, 0, buffer.size)
            Log.d(this.tag, "citescaudio cu lungimea de " + buffer.size);
            if (read > 0) {
                val packet = DatagramPacket(buffer, read, address, port)
                Log.d(this.tag, "trimitem packet" + packet.length);
                sharedSocket?.send(packet)
            }
        }

        recorder.stop()
        recorder.release()
    }
}
