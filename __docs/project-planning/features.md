# Future Features


## Tickets from Customer:

### Pain Tracking 
- [ ] **Pain Tracking**
  - [ ] scale of 1-10
  - [ ] where pain is on body,
    - [ ] visual front and back view of body, w/ selectable parts of body to show WHERE pain is
    - [ ] drop down menus with list of part of body to select where pain is
    - [ ] collapsable rows where the title is something like 'legs', 'arms', 'torso', 'head', etc. When these rows expand it is a multiselect element with more specific options, for example under legs it could say 'calf', 'thigh', 'glutes', 'hips', 'hamstrings', 'ankles', 'feet', etc.

### Data To Track

#### UX Notes:
- Mimic Clue app layout from video 
- Individual row for each data item (emotions, sleep quality, etc), customizable cards to fill each row so each unique data point can utilize the same row components and cards (very similar to show-tracker project).
- User should be able to customize the order of the data rows on the pages where they appear.

#### Everyday (the whole cycle: ~28 day):
- Emotions (Multiple Selection)
  - Fine
  - Mood Swings
  - Sensitive
  - Angry
  - Irritable
  - Anxious
  - Insecure
  - Sad/Depressed
- Sleep Quality (Multiple Selection)
  - Trouble Falling Asleep
  - Trouble Staying Asleep/Restless Sleep
  - Trouble Waking Up/ Waking Up Tired
  - Woke Up Feeling Rested
- Sex/Sex Life (Multiple Selection)
  - Protected
  - Unprotected
  - No Sex
  - High Drive
  - Low Drive
  - Sex Toys
  - Orgasm
  - Pain During
- Energy (Single Selection)
  - Exhausted
  - Tired
  - Okay
  - Energetic
  - Fully Energized
- Discharge (Multiple Selection)
  - Sticky
  - Creamy
  - Watery
  - Clumpy
  - White
  - Yellow
  - None
- Skin/Face (Multiple Selection)
  - Fine
  - Acne/Breakouts
  - Dry
  - Oily
  - Itchy
  - Red
  - Inflamed
  - Puffy
- Digestion (Multiple Selection)
  - Fine
  - Bloating
  - Gas
  - Heartburn/Acid Reflux
  - Nausea
  - Diarrhea
  - Constipation

#### During Mensuration (when tracking blood flow):
- Blood Flow (Single Selection)
  - Light
  - Medium
  - Heavy
  - Super Heavy
- Collection Method (Single Selection)
  - Tampon
  - Pad
  - Panty Liner
- Mind (Multiple Selection)
  - Productive
  - Unproductive
  - Motivated
  - Unmotivated
  - Focused
  - Distracted
  - Calm
  - Stressed
  - Brain Fog
  - Clear Headed
  - Forgetful
- Pain (Multiple Selection)
  - None
  - Rated on a scale of 1-10
  - Location (Multiple Selection)
    - Head & Neck
      - Front Headache
      - Back Headache
      - Migraine
      - Sinus Pressure
      - Neck
    - Back
      - Lower Back
      - Upper Back
      - Kidneys
    - Abdomen
      - Stomach
      - Uterus
      - Ovaries
      - Pelvis
    - Legs
      - Sciatic Nerve
      - Groin
      - Tailbone
      - Thighs
    - Vagina
      - Clitoris
      - Vulva
      - Cervix


### Analytics: Ovulation Prediction
The user should be able to view a calendar with predicted ovulation days based on their cycle history. This will help them identify their fertile window for family planning purposes.

#### UX Notes
- Calendar,
- Red days are predicted period days
- Blue days are predicted ovulation days


### Analytics: Cycle Length (Avg)
The user should be able to see a number representing their average cycle length, calculated from their logged period data. This provides insight into their menstrual health and helps with tracking irregularities.


### Analytics: Cycle Variation (Avg)
A number statistic that shows the average variation in cycle length over the past 6 months. This metric can help users understand how consistent their cycles are, which can be important for tracking and predicting future cycles. A lower variation indicates more regular cycles, while a higher variation may suggest irregularity.


### Analytics: Period Length (Avg)
The user should be able to see the average number of days they spend bleeding per cycle in the last 'X' number of cycles. 
The user should be able to view a data visualization of number of days in each cycle they have bled. Each data point in the graph should represent a single cycle, and the y-axis should represent the number of days in that cycle. The x-axis can represent time (e.g., by cycle start date) or simply be an index of cycles (e.g., Cycle 1, Cycle 2, etc.). The graph should allow the user to easily identify trends in their cycle length over time, such as whether their periods are getting longer, shorter, or remaining consistent.


### Sleep Predictions
Based on the history of the users logged sleep quality throughout their cycles, the system should be able to give predictions on how the user will sleep during a given phase of their cycle. (Mensuration Phase, Follicular Phase, Ovulation Phase, Luteal Phase)



## Technical Notes:

- [ ] add husky implementation for pre-commit and pre-push hooks to run linting, formatting, and type checks
- [ ] Implement git branching rules
  - [ ] main branch is protected, only PRs from `version-x.x.x` branches allowed
  - [ ] the main branch should always hold the latest stable release version, for example if the latest release is version 1.0.0 then the main branch should always reflect the code for version 1.0.0
  - [ ] version branches are named `version-x.x.x` and protected. Only PRs from `feature/*` and `bugfix/*` branches allowed.
  - [ ] feature branchs should be created for a single feature or a group of closely related features, this way we can have clear PRs that show what was added so when we generate the release notes in gitlab, it will show all the features that were added in that release.
  - [ ] feature branches are named `feature/short-description` and can be merged into version branches when ready
  - [ ] bugfix branches are named `bugfix/short-description` and can be merged into version branches when ready
  - [ ] feature branches should be created from their corresponding version branch, for example if we are working on version 1.0.0 then feature branches should be created from `version-1.0.0` branch
  - [ ] bugfix branches should be created from their corresponding version branch, for example if we are working on version 1.0.0 then bugfix branches should be created from `version-1.0.0` branch
- [ ] Add a changelog that gets updated whenever something is changed or added to the project. Implementation details (which files changed) are too granular for a changelog — that level of detail belongs in a commit message or PR description. A changelog is for users/teammates, not a code audit trail. **A changelog entry should answer "what can I do now that I couldn't before?" not "what files were touched?"**
- [x] Frontend, backend, and keycloak should all have their own docker containers so this project can be easily run locally for dev, and then easily deployed for production.
- [ ] 