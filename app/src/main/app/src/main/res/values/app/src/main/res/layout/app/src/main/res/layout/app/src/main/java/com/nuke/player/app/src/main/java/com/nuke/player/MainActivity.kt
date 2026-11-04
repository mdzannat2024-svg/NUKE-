package com.nuke.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var empty: TextView
    private var data: Map<String, List<Video>> = emptyMap()
    private var openFolder: String? = null

    private val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val askPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) load() else { empty.text = "Allow video access to see your videos"; empty.visibility = View.VISIBLE }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        title = findViewById(R.id.title)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openFolder != null) { openFolder = null; show() } else finish()
            }
        })

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) load()
        else askPerm.launch(perm)
    }

    private fun load() { data = Videos.scan(this); show() }

    private fun show() {
        val folder = openFolder
        val rows: List<Row> = if (folder == null) {
            title.text = "NUKE"
            data.map { (name, vids) ->
                Row("📁  $name", "${vids.size} video${if (vids.size > 1) "s" else ""}") {
                    openFolder = name; show()
                }
            }
        } else {
            title.text = folder
            val vids = data[folder].orEmpty()
            vids.mapIndexed { i, v ->
                Row(v.name, "${Videos.fmtTime(v.durationMs)}  •  ${Videos.fmtSize(v.size)}") {
                    Queue.items = vids; Queue.index = i
                    startActivity(Intent(this, PlayerActivity::class.java))
                }
            }
        }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = RowAdapter(rows)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED && data.isNotEmpty()) {
            data = Videos.scan(this); show()
        }
    }
}

data class Row(val title: String, val sub: String, val onClick: () -> Unit)

class RowAdapter(private val rows: List<Row>) : RecyclerView.Adapter<RowAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
    override fun onBindViewHolder(h: VH, i: Int) {
        val r = rows[i]
        h.name.text = r.title; h.sub.text = r.sub
        h.itemView.setOnClickListener { r.onClick() }
    }
    override fun getItemCount() = rows.size
}
