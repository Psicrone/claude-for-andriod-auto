# Publishing on Google Play

Everything the app needs for Google Play is in this repository. A few steps can only be done by the
developer in person (paying, identity verification, accepting agreements), so they are listed first.
Nothing is uploaded to Play until someone runs the **Google Play** workflow with "upload" ticked.

## Read this first: what Play allows today

- **Android Auto templated messaging apps are in beta.** Google lets anyone publish them to the
  **internal testing** and **closed testing** tracks, but rejects builds on **open testing** and
  **production** until the beta ends ([Android Developers](https://developer.android.com/training/cars/communication/templated-messaging)).
  Claude Auto's car screen is a templated messaging app, so for now it can go to testers, not to
  everyone. You can ask for early access with the "nominate yourself" form on that page. The
  workflow only offers the internal and closed (`alpha`) tracks for this reason.
- **New personal developer accounts must run a closed test** with at least 12 testers who stay
  opted in for 14 days before they can apply for production. That fits the beta limit above: the
  closed test is the useful step either way.
- **AI-generated content.** Play requires apps whose main feature is an AI chatbot to let people
  report offensive responses without leaving the app. Each Claude reply in the phone app now has a
  flag button that does this (it emails the report to `CLAUDE_AUTO_SUPPORT_EMAIL`).
- **The name.** "Claude" is Anthropic's trademark, and Play removes apps that look like they come
  from another company. The listing therefore uses **Drive Mode for Claude Code** and says plainly
  that it is not made by Anthropic. Expect the review to look at the name and the asterisk icon;
  if Play objects, rename the app (`app_name` in `strings.xml`) and change the icon.

## Steps only you can do

1. **Create a Google Play developer account** at <https://play.google.com/console/signup>:
   personal account, US$25 one-time fee, ID verification, and a verified phone number and email.
   Verification can take a few days.
2. **Make the upload key** on your computer, from this repository folder (needs a JDK and the
   GitHub CLI signed in with `gh auth login`):
   ```bash
   scripts/play/make-upload-key.sh
   ```
   It creates `claude-auto-upload.jks` and sets the four signing secrets on GitHub. Back the file
   and its password up in a password manager. Don't commit it (`*.jks` is ignored).
3. **Set where reports go:** repository Settings → Secrets and variables → Actions → Variables →
   New variable `CLAUDE_AUTO_SUPPORT_EMAIL` = an email address you read. It is also the contact
   email on the Play listing, which is public.
4. **Tag and build the first bundle.** Tag a release as usual (e.g. `v0.3.1`), then Actions →
   **Google Play** → Run workflow with that tag and "upload" off. Download the `.aab` from the run's
   artifacts.
5. **Create the app in Play Console** (Create app → name "Drive Mode for Claude Code", App, Free),
   then:
   - Test and release → Setup → **App signing**: keep "Use Play App Signing" (Google holds the key
     phones see; your file is only the upload key).
   - Test and release → **Internal testing** → Create release → upload the `.aab` by hand. The
     first upload has to be by hand; Play's API can't create a new app.
   - Advanced settings → **Form factors** → add **Android Auto** and accept its terms.
6. **Fill in the store listing and policy forms** with the answers below (copy and paste).
7. **Let the workflow upload from then on.** In Play Console go to Users and permissions → invite
   a service account (create it in Google Cloud: IAM → Service accounts → Create, then Keys → Add
   key → JSON). Give it the "Release apps to testing tracks" permission for this app only. Save the JSON as the repository secret `PLAY_SERVICE_ACCOUNT_JSON`.
8. **Run the closed test.** Test and release → Closed testing → create a track, add 12+ testers
   (a Google Group or email list), promote the internal build, and keep it running for 14 days.

After that, every release is: tag, run **Google Play** with "upload" on, track `internal` or
`alpha`, status `draft` (review it in Play Console, then roll out) or `completed`.

## Signing plan

- **Play App Signing** holds the app signing key. The key in `claude-auto-upload.jks` is only the
  upload key. If it's lost or leaked, Play Console can reset it (App signing → Request upload key
  reset); the app keeps updating normally.
- The **GitHub release APK** is signed with the same upload key once the secrets exist, but Play
  installs are signed by Google's key. Android treats them as different signers, so a phone with
  the GitHub APK has to uninstall it before installing from Play (and the other way round).
- **Version codes** come from the tag: `v1.2.3` is `1020300`, so every tag is higher than the last.
  To upload the same tag again (Play never accepts a version code twice), run the workflow with
  `build` set to 1, 2, and so on.

## Store listing

The text is in [`play/listing/en-US/`](../play/listing/en-US): `title.txt` (30 characters max),
`short_description.txt` (80) and `full_description.txt` (4000). Release notes for uploads come from
[`play/whatsnew/whatsnew-en-US`](../play/whatsnew/whatsnew-en-US) (500 max); update it before
each upload.

You also need, under Grow users → Store presence → Main store listing:

- App icon, 512 × 512 PNG (export the launcher icon).
- Feature graphic, 1024 × 500 PNG.
- At least 2 phone screenshots, and for Android Auto, at least 2 car screenshots taken with the
  Desktop Head Unit (DHU) at 1024 × 768 or larger; Play checks that they show the car screen.
- Category: **Productivity**. Contact email: the same address as `CLAUDE_AUTO_SUPPORT_EMAIL`.

## App content (policy forms)

**Privacy policy URL:** <https://github.com/Psicrone/claude-for-andriod-auto/blob/main/PRIVACY.md>

**App access:** "All or some functionality is restricted." Reviewers need a backend to talk to.
The simplest is cloud mode: give reviewers an Anthropic API key with a low spending limit (create
it in the Anthropic Console and delete it after review) and a GitHub token for a public test repo.
Instructions to paste: "Open the app → Get started → Cloud → paste the API key and GitHub token →
add the repository → Done. Tap the mic or type a message, e.g. 'list the files in this repo'."
These credentials are only visible to Google.

**Ads:** No.

**Content rating questionnaire:** category "All other app types". Violence, sexuality, language,
controlled substances, gambling: No. Users interact or exchange content with each other: No (you
only talk to an AI). Shares location: No. Digital purchases: No. If asked whether the app generates
content with AI or contains a chatbot: Yes. Expect a 12+/Teen rating.

**Target audience:** 18 and over only (keeps the app out of the Families program).

**News app:** No. **COVID-19 contact tracing:** No. **Government app:** No. **Financial
features:** None. **Health:** None.

**Data safety** (what to tick):

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Which account creation methods does your app support? | None (my app doesn't support account creation) |
| Data deletion URL | Not needed without accounts; leave empty or use the PRIVACY.md link |

Data types (everything else: not collected):

| Data type | Collected | Shared | Processed ephemerally | Required | Purposes |
| --- | --- | --- | --- | --- | --- |
| Messages → Other in-app messages | Yes | No | No | Yes | App functionality |

Why: what you type or dictate leaves the phone for the backend you chose (your bridge or
Anthropic's API), which counts as collection even though no developer server is involved. Sending
it to Anthropic in cloud mode is a transfer the user starts and expects, which Play exempts from
"sharing". Audio isn't listed because the app doesn't send audio; the phone's speech recognition
service does that under its own policy. API keys, tokens and the bridge address stay on the phone
except to authenticate with their own service.

**Permissions:** `RECORD_AUDIO` (dictation), `POST_NOTIFICATIONS` (Claude's replies) and
`INTERNET` need no declaration form. The `<queries>` entries name specific packages, so there is
no "all apps" declaration either.

## Android Auto checklist

Checked against the [car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality)
and the templated messaging requirements:

- [x] `CarAppService` declares `androidx.car.app.category.MESSAGING`; `minCarApiLevel` is 7.
- [x] `automotive_app_desc.xml` declares both `template` and `notification`.
- [x] Messages are also posted as `MessagingStyle` notifications with a Reply action
      (`SEMANTIC_ACTION_REPLY`, `RemoteInput`, no UI) and Mark as read
      (`SEMANTIC_ACTION_MARK_AS_READ`, no UI), so Android Auto can read and answer them.
- [x] The car screen only uses Car App Library templates; there's no typing in the car, only voice.
- [x] No ads, video or games in the car.
- [ ] Test on the Desktop Head Unit and in a real car: start a conversation, dictate, hear the
      reply, approve and refuse a tool call, reply from a notification while another app is on
      screen, and check that nothing needs the phone while driving.
- [ ] Take the car screenshots for the listing while doing the above.

## Workflow reference

`.github/workflows/play.yml`, run by hand from the Actions tab:

| Input | Meaning |
| --- | --- |
| `tag` | Release tag to build, e.g. `v0.3.1` |
| `build` | 0 normally; 1 to 99 to re-upload the same tag |
| `upload` | Off: only build the `.aab` (artifact). On: also send it to Play |
| `track` | `internal` or `alpha` (closed testing) |
| `status` | `draft` (finish the rollout in Play Console) or `completed` (roll out to testers now) |

| Secret or variable | What it is |
| --- | --- |
| `CLAUDE_AUTO_KEYSTORE_BASE64`, `CLAUDE_AUTO_KEYSTORE_PASSWORD`, `CLAUDE_AUTO_KEY_ALIAS`, `CLAUDE_AUTO_KEY_PASSWORD` | Upload key, set by `scripts/play/make-upload-key.sh` |
| `PLAY_SERVICE_ACCOUNT_JSON` (secret) | Service account key from step 7 |
| `CLAUDE_AUTO_SUPPORT_EMAIL` (variable) | Where reported responses go |
