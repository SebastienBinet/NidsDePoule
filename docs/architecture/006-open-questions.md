# ADR-006: Open Questions and Future Decisions

**Status:** Living document
**Date:** 2025-02-17

## Questions for Sébastien

### Q1: Accelerometer Sampling Rate
The default plan is 50 Hz (50 readings/second). Higher rates (100-200 Hz)
capture faster impacts but use more battery. Is 50 Hz acceptable, or do you
have a preference?

### Q2: Hit Waveform Detail
The current design captures ~15 samples (300ms window) around the peak of
each hit. This gives a rough shape of the impact. Do you want more detail
(50+ samples, 1 second window) at the cost of larger messages, or is the
rough shape sufficient?

### Q3: GPS Accuracy Requirements
GPS on phones is typically 3-10 meters accurate. This means pothole positions
will have ~5m uncertainty. For clustering hits into potholes, this is workable
but not precise. Is this acceptable, or do you want to explore ways to improve
accuracy (e.g., using Google's Fused Location Provider for better estimates)?

### Q4: Multiple Vehicles / Phones
Could a user have multiple phones (e.g., a tablet and a phone both in the
car)? Should we handle deduplication of hits from the same vehicle?

### Q5: Privacy & Data Retention
How long should raw hit data be stored? Options:
- Keep forever (disk is cheap, ~600 KB/hour at peak).
- Delete raw data after aggregation (e.g., after potholes are identified).
- Let users request deletion of their data.

### Q6: City Admin Authentication
When city admin features are added (Phase 4), how should city employees
authenticate? Options:
- Shared password per city (simple but insecure).
- Individual accounts with city role.
- OAuth with city's existing identity provider.

### Q7: Pothole Identity
When multiple users hit the same pothole, how should we define "same pothole"?
Options:
- Geographic proximity (e.g., hits within 10 meters of each other).
- Geographic proximity + road segment matching (using OpenStreetMap data).
- Manual review in admin dashboard.

### Q8: Internationalization
The app will be used in France initially (based on the French project name).
Should the UI be in French only, or bilingual (French + English)?

### Q9: Google Maps API Billing
Google Maps requires a billing account even for free-tier usage. Are you
comfortable setting up a Google Cloud billing account? The free tier ($200/month
credit) is more than sufficient for this project's scale.

### Q10: Android App Name and Package
- App name: "NidsDePoule" or a more user-friendly name?
- Package name suggestion: `fr.nidsdepoule.app` or another domain?

## Decisions Deferred

| Topic                     | When to decide      | Depends on            |
|---------------------------|---------------------|-----------------------|
| Web dashboard framework   | Phase 3 start       | Complexity of UI needs |
| Google Sign-In details    | Phase 4 start       | Friend feature design  |
| City admin portal design  | Phase 4 start       | City partnership needs |
| Machine learning for hits | After real data collected | Data quality/quantity |
| Pothole clustering algo   | Phase 3             | Real-world GPS accuracy |
| App distribution method   | When >10 testers    | Tester feedback        |
