package com.mediatech.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val picker =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit().putString("tree", uri.toString()).apply()
                    refreshSummary()
                    Toast.makeText(requireContext(), "מיקום השמירה עודכן", Toast.LENGTH_SHORT).show()
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("save_location")?.setOnPreferenceClickListener {
                picker.launch(null); true
            }
            findPreference<Preference>("reset_location")?.setOnPreferenceClickListener {
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().remove("tree").apply()
                refreshSummary()
                Toast.makeText(requireContext(), "הוחזר לברירת מחדל", Toast.LENGTH_SHORT).show()
                true
            }
            refreshSummary()
        }

        private fun refreshSummary() {
            val t = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("tree", null)
            findPreference<Preference>("save_location")?.summary =
                if (t == null) "DCIM/Camera (ברירת מחדל)"
                else Uri.decode(Uri.parse(t).lastPathSegment ?: "תיקייה מותאמת")
        }
    }
}
