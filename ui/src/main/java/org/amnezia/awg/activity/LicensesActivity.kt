/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityLicensesBinding

/**
 * Licenses of the components this client is built from, and the link to the source of the fork.
 * Apache-2.0 and GPL components require the notices and the source to travel with the APK.
 */
class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.licensesBody.text = getString(R.string.licenses_body)
    }
}
