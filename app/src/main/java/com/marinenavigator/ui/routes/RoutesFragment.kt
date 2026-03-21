package com.marinenavigator.ui.routes

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinenavigator.data.models.Route
import com.marinenavigator.databinding.FragmentRoutesBinding
import com.marinenavigator.databinding.ItemRouteBinding
import com.marinenavigator.ui.map.MapViewModel
import com.marinenavigator.utils.NavigationUtils
import java.text.SimpleDateFormat
import java.util.*

class RoutesFragment : Fragment() {

    private var _binding: FragmentRoutesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoutesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = RouteAdapter(
            onViewClick = { route ->
                viewModel.loadRouteOnMap(route.id)
                // Navegar de vuelta al mapa
                requireActivity().supportFragmentManager.popBackStack()
            },
            onDeleteClick = { route ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Eliminar ruta")
                    .setMessage("¿Eliminar '${route.name}'? Esta acción no se puede deshacer.")
                    .setPositiveButton("Eliminar") { _, _ -> viewModel.deleteRoute(route) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        binding.recyclerRoutes.layoutManager = LinearLayoutManager(context)
        binding.recyclerRoutes.adapter = adapter

        viewModel.allRoutes.observe(viewLifecycleOwner) { routes ->
            adapter.submitList(routes)
            binding.tvEmpty.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
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
class RouteAdapter(
    private val onViewClick: (Route) -> Unit,
    private val onDeleteClick: (Route) -> Unit
) : RecyclerView.Adapter<RouteAdapter.VH>() {

    private var routes: List<Route> = emptyList()

    fun submitList(list: List<Route>) {
        routes = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemRouteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = routes.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val route = routes[position]
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        with(holder.binding) {
            tvRouteName.text = route.name
            tvRouteDate.text = fmt.format(Date(route.startTime))
            tvRouteDistance.text = NavigationUtils.formatDistance(route.totalDistanceMeters)
            tvRouteDuration.text = NavigationUtils.formatDuration(route.totalDurationSeconds)
            tvRouteSpeed.text = "Vel. media: %.1f kt  |  Máx: %.1f kt".format(
                route.avgSpeedKnots, route.maxSpeedKnots
            )
            btnViewRoute.setOnClickListener { onViewClick(route) }
            btnDeleteRoute.setOnClickListener { onDeleteClick(route) }
        }
    }
}
