package io.github.nobooooody.intent_modifier.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.nobooooody.intent_modifier.R

class AppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private var allApps: List<AppInfo> = emptyList()
    private lateinit var loadingContainer: View
    private lateinit var contentContainer: View
    private lateinit var searchInput: com.google.android.material.textfield.TextInputEditText

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

        searchInput = findViewById(R.id.searchInput)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        loadingContainer = findViewById(R.id.loadingContainer)
        contentContainer = findViewById(R.id.contentContainer)

        adapter = AppAdapter(emptyList()) { pkg ->
            val result = Intent().putExtra("package", pkg)
            setResult(RESULT_OK, result)
            finish()
        }
        recyclerView.adapter = adapter

        searchInput.setOnEditorActionListener { _, _, _ ->
            val query = searchInput.text?.toString() ?: ""
            adapter.filter(query)
            true
        }

        loadApps()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, Menu.FIRST, Menu.NONE, R.string.refresh)
            .setIcon(android.R.drawable.ic_menu_rotate)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == Menu.FIRST) {
            loadApps()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadApps() {
        loadingContainer.visibility = View.VISIBLE
        contentContainer.visibility = View.GONE

        Thread {
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != packageName }
                .map { AppInfo(it.packageName, it.loadLabel(packageManager).toString()) }
                .sortedBy { it.label.lowercase() }

            runOnUiThread {
                allApps = apps
                adapter.updateApps(allApps)
                adapter.filter("")
                loadingContainer.visibility = View.GONE
                contentContainer.visibility = View.VISIBLE
            }
        }.start()
    }

    data class AppInfo(val packageName: String, val label: String)

    class AppAdapter(
        private var apps: List<AppInfo>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var filtered = apps.toMutableList()

        fun updateApps(newApps: List<AppInfo>) {
            apps = newApps
            filter("")
        }

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