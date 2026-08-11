# JobTrail

A Spring Boot app for running job-hunting outreach: it sends your emails for
real, chases the ones nobody answered, and paces every send so a batch never
looks like a blast to the receiving mail provider.

![Java](https://img.shields.io/badge/Java-17-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-green)

---

## Running it

You need **Java 17+**. Maven is not required — the wrapper downloads it.

```powershell
.\mvnw.cmd spring-boot:run
```

Then open <http://localhost:8080>.

On Linux/macOS use `./mvnw spring-boot:run`. To build a jar instead:

```powershell
.\mvnw.cmd package
java -jar target\jobtrail-1.0.0.jar
```

Data lives in **PostgreSQL**, so you need a server running locally. Create the
database once:

```sql
CREATE DATABASE jobtrail;
CREATE USER jobtrail WITH PASSWORD 'jobtrail';
GRANT ALL PRIVILEGES ON DATABASE jobtrail TO jobtrail;
\c jobtrail
GRANT ALL ON SCHEMA public TO jobtrail;
```

That last `GRANT` matters on PostgreSQL 15 and newer, where `public` is no longer
writable by default — without it the app fails to create its tables on first boot.
Hibernate builds the schema itself, so there is nothing else to run.

Point the app somewhere else with `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`
and `DB_PASSWORD` if the defaults above do not match your setup.

**Credentials never live in this repository.** `application.yml` reads them from
the environment with blank defaults; for local development put real values in
`config/application.yml`, which is git-ignored and which Spring Boot loads ahead
of the packaged configuration with no profile flag needed.

> **Deploying?** See **[DEPLOY.md](DEPLOY.md)** — containerised, on Kubernetes
> (k3s) on a single EC2 instance, deployed by GitHub Actions on every push to
> `main`. It starts from what this app needs of a cluster (single instance,
> persistent Postgres, x86_64 for the embedding model) and derives the
> configuration from that.

The dashboard starts empty; **Load demo data** fills it with eight sample
threads so you can see the charts and the pipeline working. Every demo address
uses the reserved `.example` domain, so none of it can ever reach a real
person, and one click removes it again.

---

## Setting up your mail account

Open **Settings** and fill in the SMTP block. Nothing is sent until this is
valid — the dispatcher reports `Not configured` and simply idles.

### Gmail

Your normal Google password will **not** work. You need an App Password:

1. Turn on 2-Step Verification at <https://myaccount.google.com/security>.
2. Go to <https://myaccount.google.com/apppasswords> and create one for "Mail".
3. Use the 16-character value it gives you as the SMTP password.

| Field | Value |
|---|---|
| Host | `smtp.gmail.com` |
| Port | `587` |
| STARTTLS | on |
| Implicit SSL | off |
| Username | your full Gmail address |
| Password | the app password |

Outlook/Office 365 is `smtp.office365.com:587`; most other providers publish
equivalent settings.

Use **Test connection** to check the credentials, and **Send test** to put a
real message in your own inbox before you send anything to a stranger.

You can also supply credentials as environment variables on first boot instead
of typing them in — `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`.
After that the values live in the settings table and the UI is the source of
truth.

---

## How the pacing works

This is the part that keeps you out of the spam folder, so it is worth
understanding. Sending happens in **one** scheduled loop, which means exactly
one email is ever in flight. After every attempt — whether it succeeded or
failed — the dispatcher parks itself:

```
gap = max(5, minIntervalSeconds) + random(0 .. jitterSeconds)
```

Four rules compose on top of each other:

| Rule | Default | What it does |
|---|---|---|
| Minimum gap | 8s | Never less than **5s**, enforced server-side |
| Random jitter | 0–4s | Stops the cadence looking machine-generated |
| Daily cap | 120 | Hard stop for the calendar day |
| Send window | off | Optionally restrict to working hours |

The 5-second floor is `jobtrail.min-interval-floor-seconds` in
`application.yml`. It is a floor, not a default: `SettingsService.update()`
clamps whatever the UI sends, so submitting `1` stores `5`. Change the property
if you want a *higher* floor.

Two more things help deliverability, both automatic:

- Every email goes out as **multipart text + HTML**, which scores better with
  spam filters than HTML alone.
- Follow-ups are threaded onto the original with `In-Reply-To` and `References`
  headers and inherit the opening subject with a single `Re:` prefix, so they
  land in the same conversation instead of arriving as a fresh cold email.

Watch it happen on the **Queue** tab — it shows the live countdown to the next
slot, the projected send time of every waiting email, and why the dispatcher is
idle when it is.

---

## Follow-ups

Each thread carries its own policy: how many days of silence to wait, and how
many follow-ups to allow. A scheduler checks every minute and queues the next
one when it falls due, where it is paced like any other email.

A thread stops generating follow-ups the moment it is answered, closed, or
reaches its limit. You can also fire one early from the row action or the
thread drawer.

**Detecting replies.** Two ways:

- Mark a thread replied by hand (one click on the row).
- Turn on **Reply detection** in Settings. It polls your inbox over IMAP,
  matches inbound senders against open threads and marks them replied
  automatically. Off by default — no mailbox is touched unless you enable it
  and supply credentials. For Gmail use `imap.gmail.com:993` with the same app
  password.

---

## Open tracking

Enabled by default. Each outgoing email embeds a 1×1 transparent GIF pointing
back at `/t/{token}.gif`; when the recipient's client loads it, the thread
flips to *Opened*.

Be aware of what this means in practice: it tells you when someone opened your
email, and many mail clients block remote images by default, so an absent open
is not proof nobody read it. Turn it off in Settings if you would rather not
embed the pixel. If you want opens tracked from outside localhost, set
`PUBLIC_BASE_URL` to a reachable address.

---

## Templates

Templates support `{{placeholder}}` tokens, filled per recipient at queue time:

`{{name}}` · `{{first_name}}` · `{{company}}` · `{{role}}` · `{{my_name}}` ·
`{{my_email}}` · `{{date}}` · `{{day}}`

Four are seeded on first boot — two openers and two follow-ups — written to be
edited rather than sent as-is. The editor shows a live preview with sample
values as you type.

Follow-up templates ignore their own subject line by design: the opening
subject is reused so the reply threads correctly.

---

## API

Everything the UI does is a plain REST call.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/stats` | Dashboard figures and the 14-day series |
| GET/POST | `/api/outreach` | List / create a thread |
| POST | `/api/outreach/bulk` | Add many recipients at once |
| GET/PUT/DELETE | `/api/outreach/{id}` | Detail with timeline / edit / delete |
| POST | `/api/outreach/{id}/queue` | Queue the opening email |
| POST | `/api/outreach/{id}/follow-up` | Queue a follow-up now |
| POST | `/api/outreach/{id}/status` | Mark replied, closed, … |
| GET | `/api/queue` | Live queue with projected send times |
| POST | `/api/queue/pause` | Pause / resume sending |
| POST | `/api/messages/{id}/cancel` \| `/retry` | Queue control |
| GET/POST/PUT/DELETE | `/api/templates` | Template CRUD |
| GET/PUT | `/api/settings` | Read / update settings |
| POST | `/api/settings/test-smtp` \| `test-email` \| `test-imap` | Diagnostics |
| POST/DELETE | `/api/demo/seed` \| `/api/demo` | Sample data |

To poke at the tables directly, connect with `psql -U jobtrail -d jobtrail`. The
interesting ones are `outreach` (one row per thread) and `email_message` (one row
per individual email, with its send status, timestamps and error text).

---

## Notes and limits

- **Single user, local app.** There is no login. Do not expose it to the
  internet as-is: the settings table holds your SMTP password in plain text,
  and anyone who can reach the port can send mail as you.
- One attachment is supported: set **Attachment** in Settings to the full path of
  your CV and it goes out with every email, including the test send. The path is
  re-checked per send, so moving or deleting the file just stops it being
  attached rather than failing the email.
- Bounces are only detected as far as SMTP reports them at send time; a later
  bounce message arriving in your inbox is not parsed.
- Sending genuine, personal, one-to-one job enquiries is the intended use.
  The pacing here makes legitimate outreach look legitimate; it is not a way to
  push bulk mail past a filter, and recipients who did not ask to hear from you
  should still be able to tell a human wrote it.
