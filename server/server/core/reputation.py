"""Device reputation scoring.

Each device accumulates a reputation score based on how often its reports
are confirmed by other devices. Higher reputation = more weight in clustering.

Model: Bayesian with prior (confirmed + 5) / (total + 10).
  - New device starts at 0.5 (neutral)
  - Devices whose reports are frequently confirmed rise toward 1.0
  - Devices whose reports are rarely confirmed sink toward 0.0

Reputation is stored per device_id and persisted alongside hit storage.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class DeviceReputation:
    device_id: str
    total_reports: int = 0
    confirmed_reports: int = 0

    @property
    def score(self) -> float:
        """Bayesian reputation: (confirmed + 5) / (total + 10). Starts at 0.5."""
        return (self.confirmed_reports + 5) / (self.total_reports + 10)

    def record_report(self, confirmed: bool) -> None:
        self.total_reports += 1
        if confirmed:
            self.confirmed_reports += 1


class ReputationStore:
    """In-memory device reputation tracker.

    Call confirm_device() when a device's report is corroborated by another device.
    Call record_report() for every hit received.
    """

    def __init__(self) -> None:
        self._devices: dict[str, DeviceReputation] = {}

    def get(self, device_id: str) -> DeviceReputation:
        if device_id not in self._devices:
            self._devices[device_id] = DeviceReputation(device_id=device_id)
        return self._devices[device_id]

    def record_report(self, device_id: str) -> None:
        """Record that a device submitted a report."""
        self.get(device_id).total_reports += 1

    def confirm_device(self, device_id: str) -> None:
        """Record that a device's report was confirmed by clustering with others."""
        self.get(device_id).confirmed_reports += 1

    def score(self, device_id: str) -> float:
        """Get the reputation score for a device."""
        return self.get(device_id).score

    def update_from_clusters(self, clusters: list) -> None:
        """Update reputations based on clustering results.

        A device's report is "confirmed" if the cluster it belongs to has
        reports from at least 2 different devices.
        """
        for cluster in clusters:
            if len(cluster.devices) >= 2:
                for device_id in cluster.devices:
                    self.confirm_device(device_id)

    def all_scores(self) -> dict[str, float]:
        """Return all device scores as a dict."""
        return {d.device_id: d.score for d in self._devices.values()}

    def summary(self) -> dict:
        """Return a summary for the /stats endpoint."""
        if not self._devices:
            return {"device_count": 0, "avg_score": 0.5}
        scores = [d.score for d in self._devices.values()]
        return {
            "device_count": len(self._devices),
            "avg_score": round(sum(scores) / len(scores), 3),
            "min_score": round(min(scores), 3),
            "max_score": round(max(scores), 3),
        }
