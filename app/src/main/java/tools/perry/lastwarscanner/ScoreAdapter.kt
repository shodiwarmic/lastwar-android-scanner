package tools.perry.lastwarscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import tools.perry.lastwarscanner.model.MemberRow
import java.util.*

class ScoreAdapter : ListAdapter<MemberRow, ScoreAdapter.MemberViewHolder>(MemberDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player_score, parent, false)
        return MemberViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvMon: TextView = view.findViewById(R.id.tvMon)
        private val tvTues: TextView = view.findViewById(R.id.tvTues)
        private val tvWed: TextView = view.findViewById(R.id.tvWed)
        private val tvThur: TextView = view.findViewById(R.id.tvThur)
        private val tvFri: TextView = view.findViewById(R.id.tvFri)
        private val tvSat: TextView = view.findViewById(R.id.tvSat)
        private val tvPower: TextView = view.findViewById(R.id.tvPower)
        private val tvKills: TextView = view.findViewById(R.id.tvKills)
        private val tvDonation: TextView = view.findViewById(R.id.tvDonation)

        fun bind(item: MemberRow) {
            tvName.text = item.name
            tvMon.text = formatScore(item.getScore("Mon"))
            tvTues.text = formatScore(item.getScore("Tues"))
            tvWed.text = formatScore(item.getScore("Wed"))
            tvThur.text = formatScore(item.getScore("Thur"))
            tvFri.text = formatScore(item.getScore("Fri"))
            tvSat.text = formatScore(item.getScore("Sat"))
            tvPower.text = formatScore(item.getScore("Power"))
            tvKills.text = formatScore(item.getScore("Kills"))
            tvDonation.text = formatScore(item.getScore("Donation"))
        }

        private fun formatScore(score: Long?): String {
            return if (score == null || score == 0L) "-" else String.format(Locale.US, "%,d", score)
        }
    }

    class MemberDiffCallback : DiffUtil.ItemCallback<MemberRow>() {
        override fun areItemsTheSame(oldItem: MemberRow, newItem: MemberRow): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: MemberRow, newItem: MemberRow): Boolean {
            return oldItem == newItem
        }
    }
}