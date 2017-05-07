package org.reekwest.http.contract.module

import org.reekwest.http.contract.X_REEKWEST_ROUTE_IDENTITY
import org.reekwest.http.contract.module.PathBinder.Companion.Core
import org.reekwest.http.core.Filter
import org.reekwest.http.core.HttpHandler
import org.reekwest.http.core.Method.GET
import org.reekwest.http.core.Request
import org.reekwest.http.core.then
import org.reekwest.http.core.with
import org.reekwest.http.filters.ServerFilters

class RouteModule private constructor(private val router: ModuleRouter) : Module {

    constructor(moduleRoot: BasePath, renderer: ModuleRenderer = NoRenderer, filter: Filter = Filter { it })
        : this(ModuleRouter(moduleRoot, renderer, ServerFilters.CatchLensFailure.then(filter)))

    override fun toRouter(): Router = router

    fun securedBy(new: Security) = RouteModule(router.securedBy(new))
    fun withRoute(new: ServerRoute) = withRoutes(new)
    fun withRoutes(vararg new: ServerRoute) = withRoutes(new.toList())
    fun withRoutes(new: Iterable<ServerRoute>) = RouteModule(router.withRoutes(new.toList()))

    companion object {
        private data class ModuleRouter(val moduleRoot: BasePath,
                                        val renderer: ModuleRenderer,
                                        val filter: Filter,
                                        val security: Security = NoSecurity,
                                        val descriptionPath: (BasePath) -> BasePath = { it },
                                        val routes: List<ServerRoute> = emptyList()) : Router {

            private val routers = routes.plus(descriptionRoute())
                .map { it.router(moduleRoot) to security.filter.then(identify(it).then(filter)) }

            private val noMatch: HttpHandler? = null

            override fun invoke(request: Request): HttpHandler? =
                if (request.isIn(moduleRoot)) {
                    routers.fold(noMatch, { memo, (router, routeFilter) ->
                        memo ?: router(request)?.let { routeFilter.then(it) }
                    })
                } else null

            fun withRoutes(new: List<ServerRoute>) = copy(routes = routes + new)
            fun securedBy(new: Security) = copy(security = new)

            private fun descriptionRoute() =
                PathBinder0(Core(Route("description route"), GET, descriptionPath)) bind
                    { renderer.description(moduleRoot, NoSecurity, routes) }

            private fun identify(route: ServerRoute): Filter {
                val routeIdentity = route.describeFor(moduleRoot)
                return Filter {
                    { req ->
                        it(req.with(X_REEKWEST_ROUTE_IDENTITY to
                            if (routeIdentity.isEmpty()) "/" else routeIdentity))
                    }
                }
            }

        }
    }
}