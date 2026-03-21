package com.marinenavigator.ui.fishing

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinenavigator.data.models.FishingPoint
import com.marinenavigator.databinding.FragmentFishingBinding
import com.marinenavigator.databinding.ItemFishingPointBinding
import com.marinenavigator.ui.map.MapViewModel
import com.marinenavigator.utils.NavigationUtils
import java.text.SimpleDateFormat
import java.util.*

class FishingFragment : Fragment() {

    private var _binding: FragmentFishingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFishingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = FishingAdapter(
            onNavigateClick = { point ->
                val wp = com.marinenavigator.data.models.Waypoint(
                    name = point.name,
                    latitude = point.latitude,
                    longitude = point.longitude
                )
                viewModel.startNavigationTo(wp)
                requireActivity().supportFragmentManager.popBackStack()
            },
            onDeleteClick = { point ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Eliminar punto")
                    .setMessage("¿Eliminar '${point.name}'?")
                    .setPositiveButton("Eliminar") { _, _ -> viewModel.deleteFishingPoint(point) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )

        binding.recyclerFishing.layoutManager = LinearLayoutManager(context)
        binding.recyclerFishing.adapter = adapter

        viewModel.fishingPoints.observe(viewLifecycleOwner) { points ->
            // Calcular distancia actual a cada punto
            val gps = viewModel.gpsData.value
            val enriched = if (gps.isValid) {
                points.sortedBy {
                    NavigationUtils.distanceMeters(gps.latitude, gps.longitude, it.latitude, it.longitude)
                }
            } else points
            adapter.submitList(enriched, gps)
            binding.tvEmpty.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────
class FishingAdapter(
    private val onNavigateClick: (FishingPoint) -> Unit,
    private val onDeleteClick: (FishingPoint) -> Unit
) : RecyclerView.Adapter<FishingAdapter.VH>() {

    private var points: List<FishingPoint> = emptyList()
    private var gps: com.marinenavigator.data.models.GpsData? = null

    fun submitList(list: List<FishingPoint>, gpsData: com.marinenavigator.data.models.GpsData? = null) {
        points = list
        gps = gpsData
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemFishingPointBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemFishingPointBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = points.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val point = points[position]
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        with(holder.binding) {
            tvFishingName.text = point.name
            tvFishingCoords.text = "${NavigationUtils.formatLatitude(point.latitude)}  ${NavigationUtils.formatLongitude(point.longitude)}"
            tvFishingDate.text = fmt.format(Date(point.createdAt))
            tvFishingDepth.text = if (point.depth != null) "Profundidad: %.1f m".format(point.depth) else ""
            tvFishingNotes.text = point.notes

            val g = gps
            if (g != null && g.isValid) {
                val dist = NavigationUtils.distanceMeters(g.latitude, g.longitude, point.latitude, point.longitude)
                val bearing = NavigationUtils.bearingDegrees(g.latitude, g.longitude, point.latitude, point.longitude)
                tvFishingDistance.text = "${NavigationUtils.formatDistance(dist)} · ${NavigationUtils.bearingName(bearing)}"
            } else {
                tvFishingDistance.text = ""
            }

            btnNavigateToFishing.setOnClickListener { onNavigateClick(point) }
            btnDeleteFishing.setOnClickListener { onDeleteClick(point) }
        }
    }
}
