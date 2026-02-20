"""Pothole cluster API endpoints."""

from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from server.core.clustering import cluster_hits, clusters_to_geojson

router = APIRouter(prefix="/api/v1")


@router.get("/potholes")
async def get_potholes() -> JSONResponse:
    """Return clustered potholes as a GeoJSON FeatureCollection.

    Reads all stored hits, clusters them spatially, and returns the result.
    """
    from server.main import get_storage

    raw_hits = get_storage().read_all_hits()
    clusters = cluster_hits(raw_hits)
    geojson = clusters_to_geojson(clusters)
    return JSONResponse(content=geojson, media_type="application/geo+json")
