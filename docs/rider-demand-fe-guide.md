# Notify me and rider demand: FE guide

When a rider's search finds nothing, they can ask to be told when a driver is heading that way, either once or every week. Drivers see which routes riders are looking for, ranked by how well each one fits the trips they already drive. In the evening, drivers get a push about strong demand on their own routes that nobody has posted yet.

All paths sit under `https://api-dev.coryd.ng`, use the normal `Authorization: Bearer …` token and the standard `{ success, message, data }` envelope.

## Checklist

**Rider app**
- [ ] Send `originLabel` and `destinationLabel` (place names) with every search
- [ ] "No rides found": show **Notify me**, with "just this time" or "repeat" (section 2)
- [ ] My requests screen: list and remove (section 2)
- [ ] Tapping either `NOTIFY_ME_MATCHED` push ("A ride is available" or "A seat just opened") opens the ride via `routePostId` (section 3)
- [ ] A full ride opened from a push shows "This ride just filled up. We'll tell you if a seat opens." plus **See other rides** (section 3)

**Driver app**
- [ ] "Riders are looking for" screen (section 4)
- [ ] **Post a ride** from a demand row pre-fills the post-route form
- [ ] "Where do you usually drive?" in onboarding, and the demand push on/off switch in settings (section 5)
- [ ] Handle the `DRIVER_DEMAND_ALERT` push - open the demand screen (section 6)

---

## 1. Search: send the place names

`POST /trip/routes/search` takes two new optional fields:

```json
{ "originLat": 6.62, "originLng": 3.51, "destinationLat": 6.43, "destinationLng": 3.42,
  "desiredDepartureTime": "2026-10-13T06:00:00Z",
  "originLabel": "Ikorodu", "destinationLabel": "Victoria Island" }
```

Every search is recorded so drivers can see which routes riders want - only as totals, never who searched. The labels are what names those routes on the driver's screen. Use the area name the rider picked, such as "Ikorodu" or "Lekki Phase 1", not a full street address.

---

## 2. Notify me (rider)

### Create: `POST /trip/routes/notify-me`

**Just this time:**
```json
{ "originLat": 6.62, "originLng": 3.51, "destinationLat": 6.43, "destinationLng": 3.42,
  "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
  "desiredDepartureTime": "2026-10-13T06:00:00Z" }
```

**Repeat**, for example weekdays around 7 AM for 4 weeks:
```json
{ "originLat": 6.62, "originLng": 3.51, "destinationLat": 6.43, "destinationLng": 3.42,
  "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
  "repeatDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
  "repeatTime": "07:00", "repeatWeeks": 4 }
```

- `repeatTime` is Lagos time, `HH:mm`. `repeatWeeks` is 1-12.
- A ride counts as a match if it starts and ends within about 4 km and leaves within about 3 hours of the rider's time, on one of their days.
- A request keeps going until `activeUntil` (for a one-off, shortly after the time asked for) and is told about each matching ride once. If the first ride fills up, the rider still hears about the next one.
- A one-off request stops as soon as the rider has a seat on one of the rides it told them about. Riders are never told about a ride they're already booked on.
- A rider can have up to 10 waiting at a time.
- `400` errors: *"Choose a departure time, or the days and time it repeats"*, *"That departure time has already passed"*, *"A repeating request needs repeatTime and repeatWeeks"*, *"You already have 10 routes you're waiting on - remove one to add another"*.

**`POST /trip/routes/notify-me/riders/{riderId}` has been removed.** Use the endpoint above for both one-off and repeating requests.

### My requests: `GET /trip/routes/notify-me`

```json
[{ "id": "…", "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
   "originLat": 6.62, "originLng": 3.51, "destinationLat": 6.43, "destinationLng": 3.42,
   "desiredDepartureTime": null, "repeatDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
   "repeatTime": "07:00", "activeUntil": "2026-11-07T23:00:00Z" }]
```

Show "Weekdays at 7:00 AM, until 7 Nov" for a repeating one, and the date and time for a one-off.

### Remove: `DELETE /trip/routes/notify-me/{id}`

---

## 3. The rider's pushes

Both use type `NOTIFY_ME_MATCHED` and carry **`routePostId`**, so tapping either should open that ride. Before, it couldn't.

**A ride is available**, when a driver posts a matching ride:

> A driver is heading from Ikorodu to Victoria Island on Tue 13 Oct at 7:00 AM. Book your seat.

**A seat just opened**, when a ride we told them about had filled up and someone cancels. Each rider gets this at most once per ride:

> A seat just opened on the Ikorodu → Victoria Island ride on Tue 13 Oct at 7:00 AM. Book it before it goes.

### If the ride is full when they open it

Seats can go between the push and the tap. The ride details come back with `bookable: false` and `unavailableReason: "This ride is full"` and booking returns the same `400`. On that screen show:

> This ride just filled up. We'll tell you if a seat opens.

That happens automatically - no button needed. Add a **See other rides** button that runs the same search again, so they can pick another driver straight away.

---

## 4. "Riders are looking for" (driver): `GET /trip/routes/demand`

```json
[{
  "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
  "originLat": 6.62, "originLng": 3.51, "destinationLat": 6.43, "destinationLng": 3.42,
  "dayType": "WEEKDAYS", "windowStart": "06:40", "windowEnd": "08:05", "typicalTime": "07:10",
  "riderCount": 12, "notifyMeCount": 5, "ridesPosted": 1,
  "relevance": "MATCHES_YOUR_ROUTES"
}]
```

| Field | Show as |
|---|---|
| `originLabel` → `destinationLabel` | The route. Either can be `null` if no rider named it - show "Near Ikorodu"-style text from the map, or the coordinates |
| `dayType` + `windowStart`/`windowEnd` | "Weekdays, 6:40-8:05 AM" - the earliest and latest times these riders want to leave (Lagos time, `HH:mm`). When they're equal, say "around 7:00 AM" |
| `typicalTime` | "Most around 7:10 AM" - the middle of the window |
| `riderCount` | "12 riders looking this week" (searches in the last 7 days plus waiting requests, each rider counted once) |
| `notifyMeCount` | "5 waiting to be notified" - they're told the moment the driver posts |
| `ridesPosted` | Rides already posted for it in the next 7 days. When above 0, say so ("1 ride already posted") |
| `relevance` | Group or label the list (below) |

| `relevance` | Means | Suggested label |
|---|---|---|
| `MATCHES_YOUR_ROUTES` | Same start, end and time as trips they drive | "Matches your routes" |
| `DIFFERENT_TIME` | Same start and end, different time | "Your route, different time" |
| `SAME_START` | Starts where they start | "Near your routes" |
| `SAME_END` | Ends where they end | "Near your routes" |
| `OTHER` | Nothing in common | Show further down |

The list is already sorted in that order, then by most riders. A route only appears once at least 3 different riders want it, so no one can be identified.

**How times are grouped:** riders rarely pick the same minute, so riders on the same route are grouped into time windows of up to 2 hours - 6:40, 6:55, 7:10, 7:30 and 8:05 are one entry of 5 riders, "6:40-8:05 AM", not five entries. Riders wanting 6 PM on the same route are a separate entry. A driver's own trips count as "same time" if they leave within an hour of the window.

**Post a ride:** open the post-route form pre-filled with the origin and destination (labels and coordinates) and the next departure on that kind of day at `typicalTime`. When the driver posts, every rider waiting on that route is pushed straight away.

---

## 5. Where the driver usually drives: `GET` / `PUT /trip/routes/drivers/me/driving-preferences`

```json
{ "startLat": 6.62, "startLng": 3.51, "startLabel": "Ikorodu",
  "endLat": 6.43, "endLng": 3.42, "endLabel": "Victoria Island",
  "mornings": true, "evenings": false, "demandAlerts": true }
```

- Ask during onboarding: *"Where do you usually drive?"* with a start area, an end area, and mornings and/or evenings. Until a driver has posted routes, this is what their demand list and pushes are matched against. Once they post, their recent trips count as well.
- `demandAlerts` is the on/off switch for the evening push - put it in settings. It's on unless they turn it off.
- `PUT` only changes the fields you send; leave a field out to keep what's saved.

---

## 6. The driver's evening push

`DRIVER_DEMAND_ALERT`, title *"Riders are looking for a ride"*. It's **sent at 7 PM the evening before**, when drivers plan tomorrow - the times in it are when the **riders** want to leave tomorrow:

> 9 riders want Ikorodu → Victoria Island tomorrow between 6:40 and 8:05 AM. Post a ride.

(or "around 7:15 AM" when every rider picked the same time)

or, when several routes qualify:

> Riders are looking for 2 routes you drive tomorrow. See them in the app and post a ride.

Open the demand screen (section 4) when it's tapped. A driver only gets it when all of these are true:
- at least 5 riders want the route
- no one has posted a ride for it yet
- it matches the driver's own trips or usual route, at the same time of day
- the driver is verified (or past the posting checks)
- they haven't had one today, and at most 3 this week
- they haven't turned demand pushes off
