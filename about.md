- In Japan I appreciated that most of the important information about trains on a particular line, even if they had different terminating staitions or service patterns, could be found at a glance on a departure timetable.
- These are not like the folding timetables that detail the schedula of a train for every stop. These are more arrowly focused to a particular stop on the train line.
- in this format, the hours of service are given on the left, and the minutes of this hour in whcih there is a departure are given on the right. If there are departures at 4:00, 4:15, 4:30, and 4:45, it will show up in the timetable like:

| Hour shown here | Minutes shown here|
|------|---------|
| 4    | 00 15 30 45 |

- Additionally, below the minute indicators, there will be labels showing the terminal stops of each trip.

- I replicate this concept in this app using GTFS data imported into OneBusAway, a versatile API for GTFS.
    - GTFS is transit schedule format. MTA publishes all its transit, including Subway, Bus, LIRR and MNR in GTFS.
- How to
    - Choose origin
        - This is most important. This format of timetable is scoped to a particular station.
    - Choose destination
        - LIRR is a heavily branched railroad. As a result, most people will be concerned only with service on their branch. A person heading for Massapequa on the South Shore will likely not be concerned about trains heading to Port Washington on the North Shore, since these are two destinations with very few intermediate stations in common. By choosing destination, a user can concern themselves with only trips that are relevant to them
        - Users can also choose to see timetables for all inbound (westbound) or all outbound (eastbound) trips. At farther west stations, users may see a rainbow of labels under the minute indicators, showing the terminal stations of these trips. Since the LIRR branches heavily, these indicators will be useful for users who need to see departures for particular branches or service patterns.