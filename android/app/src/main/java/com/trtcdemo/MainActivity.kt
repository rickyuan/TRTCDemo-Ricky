package com.trtcdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.trtcdemo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

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
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill defaults
        binding.editUserId.setText(Config.DEFAULT_USER_ID)

        binding.btnStartCall.setOnClickListener { onStartCallClicked() }
        binding.btnLanguage.setOnClickListener { toggleLanguage() }
    }

    private fun onStartCallClicked() {
        val userId   = binding.editUserId.text.toString().trim()
        val toUserId = binding.editToUserId.text.toString().trim()

        if (userId.isEmpty()) { binding.editUserId.error = getString(R.string.error_enter_user_id); return }
        if (toUserId.isEmpty()) { binding.editToUserId.error = getString(R.string.error_enter_remote_id); return }

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

        lifecycleScope.launch {
            try {
                // Fetch UserSig from the backend server — no secrets in the client
                val userSig = ServerApi.getUserSig(userId)
                if (userSig == null) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_usersig_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val roomId = (100000..999999).random()

                val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_USER_ID,    userId)
                    putExtra(CallActivity.EXTRA_TO_USER_ID, toUserId)
                    putExtra(CallActivity.EXTRA_USER_SIG,   userSig)
                    putExtra(CallActivity.EXTRA_SDK_APP_ID, Config.SDK_APP_ID)
                    putExtra(CallActivity.EXTRA_ROOM_ID,    roomId)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_generic, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Toggle between English and Chinese using AppCompatDelegate per-app locale.
     * AppCompat 1.6+ persists the choice automatically.
     */
    private fun toggleLanguage() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val isZh = !currentLocales.isEmpty && currentLocales.toLanguageTags().startsWith("zh")
        if (isZh) {
            // Switch to English (system default)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            // Switch to Chinese
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
        }
        // Activity is automatically recreated by AppCompat after locale change
    }

    private fun setLoading(loading: Boolean) {
        binding.btnStartCall.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
