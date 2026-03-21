package com.marinenavigator.ui.waypoints

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinenavigator.data.models.Waypoint
import com.marinenavigator.databinding.FragmentWaypointsBinding
import com.marinenavigator.databinding.ItemWaypointBinding
import com.marinenavigator.ui.map.MapViewModel
import com.marinenavigator.utils.NavigationUtils

class WaypointsFragment : Fragment() {

    private var _binding: FragmentWaypointsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWaypointsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = WaypointAdapter(
            onNavigateClick = { wp ->
                viewModel.startNavigationTo(wp)
                requireActivity().supportFragmentManager.popBackStack()
            },
            onDeleteClick = { wp ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Eliminar waypoint")
                    .setMessage("¿Eliminar '${wp.name}'?")
                    .setPositiveButton("Eliminar") { _, _ -> viewModel.deleteWaypoint(wp) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )

        binding.recyclerWaypoints.layoutManager = LinearLayoutManager(context)
        binding.recyclerWaypoints.adapter = adapter

        viewModel.waypoints.observe(viewLifecycleOwner) { wps ->
            val gps = viewModel.gpsData.value
            val sorted = if (gps.isValid) {
                wps.sortedBy {
                    NavigationUtils.distanceMeters(gps.latitude, gps.longitude, it.latitude, it.longitude)
                }
            } else wps
            adapter.submitList(sorted, gps)
            binding.tvEmpty.visibility = if (wps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class WaypointAdapter(
    private val onNavigateClick: (Waypoint) -> Unit,
    private val onDeleteClick: (Waypoint) -> Unit
) : RecyclerView.Adapter<WaypointAdapter.VH>() {

    private var waypoints: List<Waypoint> = emptyList()
    private var gps: com.marinenavigator.data.models.GpsData? = null

    fun submitList(list: List<Waypoint>, gpsData: com.marinenavigator.data.models.GpsData? = null) {
        waypoints = list
        gps = gpsData
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemWaypointBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemWaypointBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = waypoints.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val wp = waypoints[position]
        with(holder.binding) {
            tvWpName.text = wp.name
            tvWpCoords.text = "${NavigationUtils.formatLatitude(wp.latitude)}  ${NavigationUtils.formatLongitude(wp.longitude)}"
            tvWpNotes.text = wp.notes

            val g = gps
            if (g != null && g.isValid) {
                val dist = NavigationUtils.distanceMeters(g.latitude, g.longitude, wp.latitude, wp.longitude)
                val bearing = NavigationUtils.bearingDegrees(g.latitude, g.longitude, wp.latitude, wp.longitude)
                tvWpDistance.text = "${NavigationUtils.formatDistance(dist)} · ${NavigationUtils.bearingName(bearing)}"
            } else {
                tvWpDistance.text = ""
            }

            btnNavigateToWp.setOnClickListener { onNavigateClick(wp) }
            btnDeleteWp.setOnClickListener { onDeleteClick(wp) }
        }
    }
}
