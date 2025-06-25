package com.example.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.nfc.Tag
import android.media.AudioTrack
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.RatingBar
import android.widget.Switch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 123
    }
    private var isRecording = false

    private lateinit var recordButton: Button
    private lateinit var ipTextInput: EditText
    private lateinit var serverConnectedSwitch: Switch
    private lateinit var disconnectButton: Button
    private lateinit var connectToServerButton: Button

    private var recordingJob: Job? = null
    private var receivingJob: Job? = null
    private val tag = "voip";

    private var SERVER_IP = InetAddress.getByName("192.168.80.98")
    private val SERVER_PORT = 41234
    private val BUFFER_SIZE = 2048
    private val SAMPLE_RATE = 16000 //Hz

    private var selectedRating: Int = 0

    private var sharedSocket: DatagramSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        ipTextInput = findViewById(R.id.ipTextInput)
        serverConnectedSwitch = findViewById(R.id.isConnectedSwitch)
        connectToServerButton = findViewById(R.id.connectToServerBtn)
        disconnectButton = findViewById(R.id.disconnectButton)

        ipTextInput.setText(SERVER_IP.toString())

        disconnectButton.setOnClickListener {
            disconnectFromServer()
            serverConnectedSwitch.isChecked = false;
        }

        serverConnectedSwitch.isClickable = false

        connectToServerButton.setOnClickListener {
            val ipStr = ipTextInput.text.toString().trim()
            if (ipStr.isEmpty()) {
                Log.d(tag, "IP invalid: este gol")
                return@setOnClickListener
            }

            try {
                val inetAddress = InetAddress.getByName(ipStr)
                SERVER_IP = inetAddress
                Log.d(tag, "Setat IP server la $ipStr")

                // Trimit un mesaj ping la server, într-un thread IO
                CoroutineScope(Dispatchers.IO).launch {
                    var connectionSuccesfully = false;
                    for (i in 0..5) {
                        try {
                            val pingMessage = "ping".toByteArray()
                            val packet = DatagramPacket(
                                pingMessage,
                                pingMessage.size,
                                SERVER_IP,
                                SERVER_PORT
                            )
                            if (sharedSocket == null)
                                sharedSocket = DatagramSocket(SERVER_PORT).apply {
                                    soTimeout = 0 // blocant
                                }
                            sharedSocket?.send(packet)
                            Log.d(tag, "Ping trimis către $ipStr:$SERVER_PORT")
                            connectionSuccesfully = true;

                        } catch (e: Exception) {
                            Log.e(tag, "Eroare la trimiterea ping-ului: ${e.message}")
                        }
                        if (connectionSuccesfully) break;
                    }
                    if (!connectionSuccesfully) {
                        Log.d(tag, "Connection failed, checked logs");
                        Toast.makeText(this@MainActivity, "conexiune esuata la server", Toast.LENGTH_SHORT).show()
                    }
                }

                // Actualizează switch-ul UI în thread-ul UI
                runOnUiThread {
                    serverConnectedSwitch.isChecked = true
                    startReceivingJob()
                }

            } catch (ex: Exception) {
                Log.e(tag, "IP invalid: $ipStr", ex)
                runOnUiThread {
                    serverConnectedSwitch.isChecked = false
                }
            }
        }

        recordButton.setOnClickListener {
            if (serverConnectedSwitch.isChecked) {
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            } else {
                Toast.makeText(this, "Nu sunteti conectat la server", Toast.LENGTH_LONG)
            }

        }
        recordButton.setOnTouchListener { _, event ->
            if (!serverConnectedSwitch.isChecked) {
                Log.w(tag, "Nu suntem conectați la server!")
                return@setOnTouchListener true
            }

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startRecording()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    stopRecording()
                }
            }
            true
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

    private fun startReceivingJob() {
        if (receivingJob?.isActive == true) return  // evită să-l pornești de mai multe ori

        receivingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer.size,
                AudioTrack.MODE_STREAM
            )

            audioTrack.play()

            try {
                while (isActive) {
                    Log.d(tag, "receive audio job")
                    sharedSocket?.receive(packet)
                    val senderIp = packet.address?.hostAddress
                    Log.d(tag, "Primit pachet de la $senderIp cu lungimea ${packet.length}")
                    val compressedAudio = packet.data.copyOf(packet.length)
                    val decompressedData = Compressor.decode(compressedAudio)
                    Log.d(tag, "Pachetul decomprimat are ${decompressedData.size}")
                    audioTrack.write(decompressedData, 0, decompressedData.size)
                }
            } catch (e: Exception) {
                Log.e(tag, "Eroare la receive: ${e.message}")
            } finally {
                audioTrack.stop()
                audioTrack.release()
            }
        }
    }

    private fun stopReceivingJob() {
        receivingJob?.cancel()
        receivingJob = null
    }


    private fun startRecording() {
        isRecording = true
        recordButton.setBackgroundColor(Color.RED)
        recordButton.text = "Stop"

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            recordAndSendAudioUDP()
        }
    }

    private fun disconnectFromServer() {
        stopRecording()
        stopReceivingJob()
        showRatingDialog {
            notifyServerDisconnect()
            runOnUiThread {
                serverConnectedSwitch.isChecked = false
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun notifyServerDisconnect() {
        val message = "DISCONNECT:" + this.getNetworkType(this)+":"+selectedRating
        val buffer = message.toByteArray()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packet = DatagramPacket(buffer, buffer.size, SERVER_IP, SERVER_PORT)
                sharedSocket?.send(packet)
                Log.d(tag, "Trimis mesaj de deconectare la server.")
            } catch (e: Exception) {
                Log.e(tag, "Eroare la trimiterea mesajului de deconectare", e)
            } finally {
                sharedSocket?.close()
                sharedSocket = null
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @SuppressLint("ServiceCast")
    private fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return "Unknown"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "Unknown"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile (4G/5G)"
            else -> "Unknown"
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.setBackgroundColor(Color.GRAY)
        recordButton.text = "Record"
        recordingJob?.cancel()
        recordingJob = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun recordAndSendAudioUDP() {
        val sampleRate = SAMPLE_RATE
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
        val headerSize = 16; //bytes


        recorder.startRecording()

        var sequence = 0L;
        while (isRecording && isActive) {
            val read = recorder.read(buffer, 0, buffer.size)
            Log.d(tag, "citescaudio cu lungimea de " + buffer.size);
            if (read > 0) {
                val timestamp = System.currentTimeMillis()
                val compressedAudioBuffer = Compressor.encode(buffer)
                val packetBuffer = ByteBuffer.allocate(headerSize + compressedAudioBuffer.size)
                // Adaugă header
                packetBuffer.putLong(sequence)
                packetBuffer.putLong(timestamp)
                // Adaugă datele audio
                packetBuffer.put(compressedAudioBuffer, 0, compressedAudioBuffer.size)
                val packetData = packetBuffer.array()
                val packet = DatagramPacket(packetData, packetData.size, SERVER_IP, SERVER_PORT)
                Log.d(tag, "trimitem packet comprimat" + packetData.size);
                sharedSocket?.send(packet)
                sequence++;
            }
        }

        recorder.stop()
        recorder.release()
    }


    private fun showRatingDialog(onRatingSelected: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rating, null)
        val ratingBar = dialogView.findViewById<RatingBar>(R.id.ratingBar)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)  // utilizatorul nu poate închide cu back sau tap afară
            .setPositiveButton("OK", null) // punem null ca să putem controla butonul manual
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            okButton.isEnabled = false // la început dezactivat

            ratingBar.setOnRatingBarChangeListener { _, rating, _ ->
                okButton.isEnabled = rating >= 1 // activăm butonul dacă rating >= 1
            }

            okButton.setOnClickListener {
                selectedRating = ratingBar.rating.toInt()
                Toast.makeText(this, "Ai ales $selectedRating stea/stele", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                // aici poți folosi selectedRating mai departe
                onRatingSelected()
            }
        }

        dialog.show()
    }

}
