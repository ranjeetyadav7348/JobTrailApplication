# Deploying JobTrail to Kubernetes on EC2

Push to `main` → tests → image to GHCR → deployed to a single-node **k3s**
cluster on one EC2 instance by GitHub Actions.

This document starts from what **this application** needs, because most of the
cluster configuration here is a consequence of one of those needs rather than a
default worth copying. For just the commands, skip to [Setup](#setup).

---

## What JobTrail requires of a cluster

| The app does this | So it needs | Which is why the manifests say |
|---|---|---|
| Runs the send dispatcher, IMAP poller and follow-up scheduler **in-process** | Exactly one instance, ever | `replicas: 1` and `strategy: Recreate` in `20-app.yaml` |
| Stores applications and their email history in Postgres | Storage that outlives a pod restart | `local-path` PVC, backed by the instance's EBS root volume |
| Reads your CV from disk to ground AI decisions | The PDF present inside the pod | `jobtrail-resume` Secret mounted at `/app/resume` |
| Loads an ONNX embedding model with **native** libraries | `linux/amd64` | x86_64 instance — **not Graviton/arm64** |
| Holds a JVM heap *plus* off-heap native model memory | ~1.5 GiB for the app | `t3.medium` minimum; requests 768Mi, limit 1536Mi |
| Loads that model and runs Hibernate DDL at boot | A slow first start not mistaken for a hang | `startupProbe` with `failureThreshold: 60` |
| Sends mail via SMTP on :587, optionally calls the Anthropic API | Outbound internet | Default VPC egress — nothing extra |
| **Has no authentication of its own** | Not to be reachable by the internet | Security group restricted to your IP |

Three of these are worth reading twice.

**Single instance is a correctness constraint, not a cost saving.** The
schedulers run inside the app. Two replicas each poll the same send queue and
the same mailbox, so a queued email goes out twice — from a tool whose entire
purpose is not irritating employers. `RollingUpdate` briefly runs two pods, which
is the same bug in miniature, hence `Recreate`. **Do not raise `replicas`** until
the schedulers sit behind a database lock such as ShedLock.

**Instance architecture is load-bearing.** The embedding model needs DJL's
tokenizer and ONNX Runtime, which ship `linux/amd64` binaries. A Graviton
(`t4g.*`) instance starts the app fine and then silently degrades retrieval to
keyword-only — no error, just quietly worse answers. This is the same reason the
model cannot load on a Windows/ARM laptop, and why deploying is what switches
hybrid search *on*.

**Storage survives reboots, not instance replacement.** k3s's `local-path`
provisioner writes to `/var/lib/rancher/k3s/storage` on the node, which is the
instance's EBS root volume. So a pod restart or a reboot keeps the data; a
terminate-and-replace loses it. That is what `k8s/40-backup.yaml` is for.

> **Why k3s rather than EKS.** k3s is upstream-conformant Kubernetes — the same
> `kubectl`, Deployments, StatefulSets, Services and Secrets — without EKS's ~$73/mo
> control-plane fee. For a single-replica personal tool it is the same workflow at
> about a fifth of the cost. Trade-offs: one node means no HA, and storage is tied
> to that node.

---

## Setup

### 0. Rotate the leaked Gmail app password — first

`psrw bthi oymz xflf` was committed to this repository. It is in git history, so
deleting it from the working tree does not make it safe.

1. Revoke it: <https://myaccount.google.com/apppasswords>
2. Generate a new one — it goes into a Kubernetes Secret in step 4, never a file.

### 1. Launch the EC2 instance

| Setting | Value | Why |
|---|---|---|
| AMI | Ubuntu Server 24.04 LTS (**x86_64**) | The image is `linux/amd64`; an arm64 instance cannot pull it |
| Type | `t3.medium` (2 vCPU, 4 GiB) | App capped at 1.5 GiB + Postgres 512 MiB + k3s itself. `t3.small` gets the app OOM-killed |
| Storage | 20 GiB gp3 | OS, container images and the Postgres volume |
| Key pair | Create or reuse | The private key becomes the `EC2_SSH_KEY` repo secret |

**Security group** — inbound only:

| Port | Source | Notes |
|---|---|---|
| 22 | Your IP (see note) | SSH, and how Actions deploys |
| 80 | **Your IP** | The app has no login. Anyone who reaches it can read your history and send mail as you. Do not open to `0.0.0.0/0`. |

> **GitHub-hosted runners have changing IPs**, so port 22 locked to your own
> address blocks the deploy job. Options, best first: install a self-hosted
> runner on the instance and drop SSH entirely; allow GitHub's published Actions
> ranges from <https://api.github.com/meta>; or widen 22 only while deploying.

### 2. Install k3s

```bash
curl -sfL https://get.k3s.io | sh -

sudo k3s kubectl get nodes    # Ready in ~30s
```

**This next part is required, not optional.** k3s writes its kubeconfig as
`root:root` mode `0600`, so the `ubuntu` user — which is what the deploy job logs
in as — cannot read it. Without this the pipeline fails on every `kubectl`:

```bash
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown "$USER:$USER" ~/.kube/config
chmod 600 ~/.kube/config

# Verify as the deploy user, with no sudo
KUBECONFIG=~/.kube/config kubectl get nodes
```

(The workflow falls back to `sudo k3s kubectl` if this file is missing, and fails
with a clear message if neither is available.)

k3s bundles Traefik and the `local-path` storage provisioner, so there is nothing
else to install — no ingress controller, no CSI driver.

### 3. Create the namespace

```bash
git clone https://github.com/ranjeetyadav7348/JobTrailApplication.git
cd JobTrailApplication
kubectl apply -f k8s/00-namespace.yaml
```

### 4. Create the secrets

Created imperatively so the values never exist in a file.

```bash
DB_PASS="$(openssl rand -base64 24)"
echo "Postgres password (save this): $DB_PASS"

kubectl -n jobtrail create secret generic jobtrail-secrets \
  --from-literal=DB_USERNAME='postgres' \
  --from-literal=DB_PASSWORD="$DB_PASS" \
  --from-literal=SMTP_USERNAME='yadavranjeet060@gmail.com' \
  --from-literal=SMTP_PASSWORD='<the NEW app password from step 0>' \
  --from-literal=ANTHROPIC_API_KEY='<sk-ant-... or leave empty>'
```

`ANTHROPIC_API_KEY` is optional — without it the app runs and the AI features
report themselves unavailable.

**The résumé** — copy it up, then mount it as a Secret:

```bash
# From your laptop
scp -i <key.pem> ~/Documents/resume/Ranjeet_Java_AI_4Exp.pdf ubuntu@<EC2_IP>:~/resume.pdf

# On the instance
kubectl -n jobtrail create secret generic jobtrail-resume \
  --from-file=resume.pdf=/home/ubuntu/resume.pdf
rm ~/resume.pdf
```

It lands read-only at `/app/resume/resume.pdf`, which is what `RESUME_PATH` in the
image points at.

**Image pull**, only if the GHCR package is private:

```bash
kubectl -n jobtrail create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username='ranjeetyadav7348' \
  --docker-password='<GitHub PAT with read:packages>'
```

Making the package public is simpler; if you do, delete the `imagePullSecrets`
block from `k8s/20-app.yaml`.

### 5. Set the public URL

`PUBLIC_BASE_URL` in `k8s/20-app.yaml` builds the open-tracking pixel URLs
embedded in outgoing email, so it must be an address the **recipient's** mail
client can resolve. Set it to the instance's public DNS name, or a real domain
pointed at it. Left as `jobtrail.local`, everything works except open tracking,
which silently records nothing.

Match `host:` in `k8s/30-ingress.yaml` to the same name.

### 6. Configure the repository

Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `EC2_HOST` | The instance's public IP or DNS name |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | The **entire** private key, `-----BEGIN` through `-----END` |
| `EC2_HOST_KEY` | Output of `ssh-keyscan -H <EC2_IP>` — pins the host so the deploy cannot be redirected |

No AWS credentials: the deploy talks to the instance over SSH, not to the AWS API.

Also create an Environment named `production` (Settings → Environments), which the
deploy job references; add a required reviewer there for a manual gate.

### 7. Deploy

```bash
git push origin main
```

First deploy takes a few minutes — Postgres initialises and Hibernate creates the
schema. If the pod never becomes ready the job fails, rolls back to the previous
revision, and prints pod status, PVC state, events and logs.

### 8. Reach it and index the résumé

If you are using the placeholder hostname, add to your machine's hosts file
(`C:\Windows\System32\drivers\etc\hosts` on Windows):

```
<EC2_PUBLIC_IP>   jobtrail.local
```

Then:

```bash
curl -X POST http://jobtrail.local/api/knowledge/reindex
curl http://jobtrail.local/api/decisions/status
```

Expect non-zero `resumeChunks` and `embeddingsReady: true`. Re-run the reindex
after editing your CV — it is idempotent, so unchanged content is detected by hash
and skipped without re-embedding.

---

## Troubleshooting

Failure modes specific to this app, and what each actually means.

| Symptom | Cause | Fix |
|---|---|---|
| Deploy fails, "permission denied" on every kubectl | `~/.kube/config` not created | Step 2 — it is required, not optional |
| Pod `Pending`, events say insufficient memory | Instance too small for the 1536Mi limit | `t3.medium` or larger |
| `ImagePullBackOff` | GHCR package private and `ghcr-pull` missing | Step 4, or make the package public |
| `CrashLoopBackOff`, logs show connection refused | Postgres not ready yet | The init container waits; check `kubectl -n jobtrail logs postgres-0` |
| Starts, but `resumeChunks: 0` | `jobtrail-resume` Secret missing, or the PDF is a scan with no text layer | Step 4; export a text-based PDF |
| `embeddingsReady: false` | arm64/Graviton instance — native libs are amd64-only | Rebuild on an x86_64 instance |
| Deploy job cannot connect at all | Security group port 22 excludes GitHub runners | See the note in step 1 |
| Open tracking records nothing | `PUBLIC_BASE_URL` left as `jobtrail.local` | Step 5 |
| **Recipients get duplicate emails** | `replicas` raised above 1 | Set it back to 1 — see the single-instance note above |
| Data gone after replacing the instance | `local-path` lives on the node's disk | Restore from the backup CronJob |

---

## Operations

```bash
kubectl -n jobtrail logs -f deployment/jobtrail
kubectl -n jobtrail get pods,pvc,svc
kubectl -n jobtrail rollout restart deployment/jobtrail
kubectl -n jobtrail rollout undo deployment/jobtrail

# Deploy a specific commit
kubectl -n jobtrail set image deployment/jobtrail \
  jobtrail=ghcr.io/ranjeetyadav7348/jobtrailapplication:<sha>

kubectl -n jobtrail exec -it postgres-0 -- psql -U postgres -d jobtrail
```

Restore a backup (if you kept `k8s/40-backup.yaml`):

```bash
aws s3 cp s3://<bucket>/jobtrail-<stamp>.sql.gz .
gunzip -c jobtrail-<stamp>.sql.gz | \
  kubectl -n jobtrail exec -i postgres-0 -- psql -U postgres -d jobtrail
```

The CronJob needs `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (scoped to one
bucket) in `jobtrail-secrets` and `BACKUP_S3_URI` in the ConfigMap; the workflow
applies it only when those exist. **This matters more here than on EKS** — the
volume is tied to the instance, so an instance replacement loses it.

---

## Cost

| Item | ~USD/month |
|---|---|
| t3.medium on-demand | 30 |
| 20 GiB gp3 | 1.60 |
| **Total** | **~32** |

A 1-year Compute Savings Plan brings the instance to roughly $19. Stopping the
instance when unused costs only the EBS. GHCR is free for public images.

For comparison, the EKS equivalent is ~$150/mo, most of it the $73 control-plane
fee — for the same single-replica app. The EKS manifests remain in this repo's
history (`git log -- k8s/`) if you want them back.
