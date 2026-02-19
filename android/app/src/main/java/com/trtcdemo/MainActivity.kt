package com.trtcdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trtcdemo.databinding.ActivityMainBinding

/**
 * Login screen.
 *
 * Collects: userId, toUserId.
 * On "Start Call":
 *  1. Validate inputs
 *  2. Request CAMERA + RECORD_AUDIO permissions if needed
 *  3. Generate UserSig locally
 *  4. Generate a roomId
 *  5. Launch CallActivity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            startCallFlow()
        } else {
            Toast.makeText(this, "需要摄像头和麦克风权限才能进行通话", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill defaults
        binding.editUserId.setText(Config.DEFAULT_USER_ID)

        binding.btnStartCall.setOnClickListener { onStartCallClicked() }
    }

    private fun onStartCallClicked() {
        val userId   = binding.editUserId.text.toString().trim()
        val toUserId = binding.editToUserId.text.toString().trim()

        if (userId.isEmpty()) { binding.editUserId.error = "请输入用户 ID"; return }
        if (toUserId.isEmpty()) { binding.editToUserId.error = "请输入对方用户 ID"; return }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startCallFlow()
        }
    }

    private fun startCallFlow() {
        val userId   = binding.editUserId.text.toString().trim()
        val toUserId = binding.editToUserId.text.toString().trim()

        setLoading(true)

        try {
            val userSig = GenTestUserSig.genTestUserSig(
                Config.SDK_APP_ID, Config.SECRET_KEY, userId
            )
            val roomId = (100000..999999).random()

            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_USER_ID,    userId)
                putExtra(CallActivity.EXTRA_TO_USER_ID, toUserId)
                putExtra(CallActivity.EXTRA_USER_SIG,   userSig)
                putExtra(CallActivity.EXTRA_SDK_APP_ID, Config.SDK_APP_ID)
                putExtra(CallActivity.EXTRA_ROOM_ID,    roomId)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "错误: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            setLoading(false)
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnStartCall.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
