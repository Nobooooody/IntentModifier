package io.github.nobooooody.intent_modifier.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import io.github.nobooooody.intent_modifier.R

class AppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            supportActionBar?.title = getString(R.string.select_app)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            toolbar.setNavigationOnClickListener { finish() }
        }

        val searchInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchInput)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        allApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .map { AppInfo(it.packageName, it.loadLabel(packageManager).toString()) }
            .sortedBy { it.label.lowercase() }

        adapter = AppAdapter(allApps) { pkg ->
            val result = Intent().putExtra("package", pkg)
            setResult(RESULT_OK, result)
            finish()
        }

        recyclerView.adapter = adapter

        searchInput?.setOnEditorActionListener { _, _, _ ->
            val query = searchInput.text?.toString() ?: ""
            adapter.filter(query)
            true
        }
    }

    data class AppInfo(val packageName: String, val label: String)

    class AppAdapter(
        private val apps: List<AppInfo>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var filtered = apps.toMutableList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(filtered[position])
        }

        override fun getItemCount() = filtered.size

        fun filter(query: String) {
            filtered = if (query.isBlank()) {
                apps.toMutableList()
            } else {
                apps.filter {
                    it.packageName.contains(query, ignoreCase = true) ||
                    it.label.contains(query, ignoreCase = true)
                }.toMutableList()
            }
            notifyDataSetChanged()
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val labelView: android.widget.TextView = itemView.findViewById(R.id.textAppLabel)
            private val pkgView: android.widget.TextView = itemView.findViewById(R.id.textAppPackage)

            fun bind(app: AppInfo) {
                labelView.text = app.label
                pkgView.text = app.packageName
                itemView.setOnClickListener { onClick(app.packageName) }
            }
        }
    }
}