# Deploying JobTrail to k3s on EC2

Docker image → GitHub Container Registry → single-node k3s on one EC2 instance,
driven by GitHub Actions.

Everything in `k8s/` and `.github/workflows/` is already written. What follows is
the part that needs your AWS account and your credentials.

---

## 0. Rotate the leaked Gmail app password — do this first

The app password `psrw bthi oymz xflf` was committed to this repository in commit
`38a63f0`. It is in git history, so deleting it from the working tree does not
make it safe; anyone who has ever cloned or forked the repo has it.

1. Go to <https://myaccount.google.com/apppasswords> and **revoke** it.
2. Generate a new one. You will paste it into a Kubernetes Secret in step 4 —
   not into any file in this repo.

The same applies to the Postgres password that was in `application.yml`. The new
one is generated in step 4 and never leaves the cluster.

> Rewriting git history (`git filter-repo`, BFG) removes the string from the repo
> but cannot un-share it. Revoking is the part that actually matters; rewriting is
> optional tidying.

---

## 1. Launch the EC2 instance

| Setting | Value | Why |
|---|---|---|
| AMI | Ubuntu Server 24.04 LTS (**x86_64**) | The image is `linux/amd64`. An arm64 instance will fail to pull it. |
| Type | `t3.medium` (2 vCPU, 4 GiB) | The app is capped at 1.5 GiB and Postgres at 512 MiB. `t3.small` (2 GiB) leaves nothing for k3s itself and the app will be OOM-killed. |
| Storage | 20 GiB gp3 | Holds the OS, container images and the Postgres volume. |
| Key pair | Create or reuse one | The private key becomes the `EC2_SSH_KEY` GitHub secret. |

**Security group** — inbound only:

| Port | Source | Notes |
|---|---|---|
| 22 | Your IP | SSH, and how GitHub Actions deploys. See the note below. |
| 80 | **Your IP** | The app has no login of its own. Anyone who can reach it can read your application history and send mail through your SMTP credentials. Do not open this to `0.0.0.0/0`. |

> **GitHub-hosted runners have changing IPs**, so port 22 restricted to your own
> IP will block the deploy job. Two options: allow GitHub's published Actions IP
> ranges (from <https://api.github.com/meta>), or — simpler and tighter — install
> a self-hosted runner on the instance and drop the SSH step entirely. For a
> personal tool, temporarily widening 22 during a deploy is also defensible; just
> don't leave it open.

---

## 2. Install k3s

SSH in, then:

```bash
curl -sfL https://get.k3s.io | sh -

# Confirm the node is Ready (takes ~30s)
sudo k3s kubectl get nodes

# Let your user run kubectl without sudo
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown "$USER:$USER" ~/.kube/config
echo 'export KUBECONFIG=~/.kube/config' >> ~/.bashrc && source ~/.bashrc

kubectl get nodes
```

k3s bundles Traefik (the ingress controller) and the `local-path` storage
provisioner, so there is nothing else to install. `local-path` writes to
`/var/lib/rancher/k3s/storage`, which is on the instance's EBS root volume — that
is what makes the Postgres volume persist across pod restarts and reboots.

---

## 3. Create the namespace

```bash
kubectl apply -f https://raw.githubusercontent.com/ranjeetyadav7348/JobTrailApplication/main/k8s/00-namespace.yaml
# or, once the repo is on the box: kubectl apply -f k8s/00-namespace.yaml
```

---

## 4. Create the secrets

These are created imperatively so the values never exist in a file. Run on the
EC2 box.

```bash
# Database. Generate a strong password rather than reusing the old one.
DB_PASS="$(openssl rand -base64 24)"
echo "Postgres password (save this somewhere safe): $DB_PASS"

kubectl -n jobtrail create secret generic jobtrail-secrets \
  --from-literal=DB_USERNAME='postgres' \
  --from-literal=DB_PASSWORD="$DB_PASS" \
  --from-literal=SMTP_USERNAME='yadavranjeet060@gmail.com' \
  --from-literal=SMTP_PASSWORD='<the NEW app password from step 0>' \
  --from-literal=ANTHROPIC_API_KEY='<sk-ant-... or leave empty>'
```

`ANTHROPIC_API_KEY` is optional. Without it the app runs normally; the assistant
and the AI mail triage report themselves unavailable.

**The résumé** — copy it to the instance, then mount it as a Secret:

```bash
# From your laptop
scp -i <key.pem> ~/Documents/resume/Ranjeet_Java_AI_4Exp.pdf ubuntu@<EC2_IP>:~/resume.pdf

# On the instance
kubectl -n jobtrail create secret generic jobtrail-resume \
  --from-file=resume.pdf=/home/ubuntu/resume.pdf
rm ~/resume.pdf
```

It lands read-only at `/app/resume/resume.pdf`, which is what `RESUME_PATH` in
the image points at.

**Image pull** — only if the GHCR package is private:

```bash
kubectl -n jobtrail create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username='ranjeetyadav7348' \
  --docker-password='<a GitHub PAT with read:packages>'
```

If you make the package public instead (Package settings → Change visibility),
delete the `imagePullSecrets` block from `k8s/20-app.yaml`.

---

## 5. Add the GitHub repository secrets

Repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `EC2_HOST` | The instance's public IP or DNS name |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | The **entire** private key file, `-----BEGIN` line through `-----END` line |
| `EC2_HOST_KEY` | Output of `ssh-keyscan -H <EC2_IP>` — pins the host so the deploy cannot be redirected to another machine |

No AWS keys are needed: the deploy talks to the instance over SSH, not to the AWS
API.

Also create an Environment named `production` (Settings → Environments) since the
deploy job references it. Add a required reviewer there if you want a manual gate.

---

## 6. Deploy

```bash
git add -A
git commit -m "Add Docker, Kubernetes and CI deployment"
git push origin main
```

Watch it under the repo's Actions tab. The workflow runs tests, builds and pushes
a `linux/amd64` image tagged with the commit SHA, then applies the manifests and
waits for the rollout. If the pod never becomes ready the job fails and prints the
pod status, recent events and the last 80 log lines.

First deploy takes a few minutes — Postgres initialises and Hibernate creates the
schema.

---

## 7. Reach the app

`jobtrail.local` is a placeholder hostname. On the machine you browse from:

```
# /etc/hosts   (C:\Windows\System32\drivers\etc\hosts on Windows)
<EC2_PUBLIC_IP>   jobtrail.local
```

Then open <http://jobtrail.local>. With a real domain, point an A record at the
instance and change `host:` in `k8s/30-ingress.yaml` and `PUBLIC_BASE_URL` in
`k8s/20-app.yaml`.

`PUBLIC_BASE_URL` matters beyond convenience: it builds the open-tracking pixel
URLs embedded in outgoing email, so it has to be an address the recipient's mail
client can resolve. Left as `jobtrail.local`, tracking silently records nothing.

---

## 8. Index the résumé

Once running, build the knowledge base the AI decisions read from:

```bash
curl -X POST http://jobtrail.local/api/knowledge/reindex
curl http://jobtrail.local/api/decisions/status
```

`status` should report a non-zero `resumeChunks`, and — unlike on a Windows/ARM
laptop — `embeddingsReady: true`. The embedding model's native libraries ship for
`linux/amd64` but not `win-aarch64`, so **containerising is what switches hybrid
retrieval on**. Locally it degrades to keyword-only search.

Re-run the reindex after editing your CV. It is idempotent: unchanged content is
detected by hash and skipped without re-embedding.

---

## Operations

```bash
# Logs
kubectl -n jobtrail logs -f deployment/jobtrail

# Pod and volume status
kubectl -n jobtrail get pods,pvc

# Restart without a redeploy
kubectl -n jobtrail rollout restart deployment/jobtrail

# Roll back to the previous image
kubectl -n jobtrail rollout undo deployment/jobtrail

# Deploy a specific past commit: re-run that commit's workflow from the Actions
# tab, or set the image directly:
kubectl -n jobtrail set image deployment/jobtrail \
  jobtrail=ghcr.io/ranjeetyadav7348/jobtrailapplication:<sha>

# Database shell
kubectl -n jobtrail exec -it postgres-0 -- psql -U postgres -d jobtrail
```

**Restore a backup** (if you kept `k8s/40-backup.yaml`):

```bash
aws s3 cp s3://<bucket>/jobtrail-<stamp>.sql.gz .
gunzip -c jobtrail-<stamp>.sql.gz | \
  kubectl -n jobtrail exec -i postgres-0 -- psql -U postgres -d jobtrail
```

---

## Things worth knowing

**One replica, and a `Recreate` strategy — both deliberate.** The app runs its
send dispatcher, IMAP poller and follow-up scheduler in-process. A second replica
would poll the same queue and mailbox and send duplicate emails, from a tool whose
purpose is not annoying employers. `RollingUpdate` has the same problem for a few
seconds per deploy, which is why the strategy is `Recreate`. Scaling out safely
means moving the schedulers behind a database lock (ShedLock or similar) first.

**Backups are yours to run.** The Postgres volume survives pod restarts and
reboots, but not the instance being terminated. `k8s/40-backup.yaml` handles that;
it needs `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (scoped to one bucket) added
to `jobtrail-secrets`, and `BACKUP_S3_URI` added to the ConfigMap. The workflow
applies it only when those keys are present.

**Rough monthly cost:** `t3.medium` on-demand ≈ $30, 20 GiB gp3 ≈ $1.60, plus
egress. A 1-year Compute Savings Plan takes the instance to roughly $19. GHCR is
free for public images. Stopping the instance when unused costs only the EBS.

**There is no authentication.** Keep the security group restricted to your own IP.
Before exposing this more widely, add TLS (cert-manager and a real domain) and an
auth layer — Traefik basic-auth middleware is the cheapest credible option.
