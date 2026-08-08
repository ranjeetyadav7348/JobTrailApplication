# Deploying JobTrail to Amazon EKS

Push to `main` → tests → container image to GHCR → deployed to EKS by GitHub
Actions. No SSH, and no AWS keys stored anywhere.

Everything in `k8s/` and `.github/workflows/deploy.yml` is written. What follows
is the part that needs your AWS account.

---

## 0. Rotate the leaked Gmail app password — do this first

The app password `psrw bthi oymz xflf` was committed in `38a63f0`. It is in git
history, so deleting it from the working tree does not make it safe — anyone who
cloned or forked the repo has it.

1. Revoke it at <https://myaccount.google.com/apppasswords>.
2. Generate a new one. It goes into a Kubernetes Secret in step 5, never into a
   file in this repo.

The Postgres password that was in `application.yml` is replaced in step 5 too.

> Rewriting history (`git filter-repo`, BFG) removes the string but cannot
> un-share it. Revoking is the part that matters.

---

## 1. Prerequisites

```bash
aws --version       # v2
eksctl version      # >= 0.190
kubectl version --client
aws sts get-caller-identity   # confirms you're authenticated
```

Set these once for the commands below:

```bash
export AWS_REGION=ap-south-1
export CLUSTER=jobtrail
export ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
export GH_REPO=ranjeetyadav7348/JobTrailApplication
```

---

## 2. Create the cluster

```bash
eksctl create cluster \
  --name "$CLUSTER" \
  --region "$AWS_REGION" \
  --version 1.31 \
  --nodegroup-name workers \
  --node-type t3.medium \
  --nodes 2 --nodes-min 2 --nodes-max 3 \
  --managed \
  --with-oidc
```

Takes roughly 15–20 minutes.

`--with-oidc` matters: it creates the cluster's IAM OIDC provider, which both the
EBS CSI driver and the GitHub Actions role depend on. Adding it later is possible
but fiddlier.

`t3.medium` because the app is capped at 1.5 GiB and Postgres at 512 MiB — a
`t3.small` (2 GiB) leaves nothing for the kubelet and system pods.

---

## 3. Install the EBS CSI driver

EKS cannot provision disks without it. Skip this and the Postgres PVC sits
`Pending` for ever, with nothing in the app's own logs to explain why.

```bash
eksctl create iamserviceaccount \
  --name ebs-csi-controller-sa \
  --namespace kube-system \
  --cluster "$CLUSTER" --region "$AWS_REGION" \
  --role-name "AmazonEKS_EBS_CSI_DriverRole_${CLUSTER}" \
  --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
  --role-only --approve

eksctl create addon \
  --name aws-ebs-csi-driver \
  --cluster "$CLUSTER" --region "$AWS_REGION" \
  --service-account-role-arn "arn:aws:iam::${ACCOUNT_ID}:role/AmazonEKS_EBS_CSI_DriverRole_${CLUSTER}" \
  --force

# Verify
kubectl -n kube-system get pods -l app.kubernetes.io/name=aws-ebs-csi-driver
```

---

## 4. Let GitHub Actions reach the cluster

Two halves: AWS must trust GitHub's OIDC provider, and the cluster must
authorise the resulting role.

**4a — the IAM trust.** If you have never used GitHub OIDC in this account:

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

**4b — the deploy role.** The trust policy is scoped to this repository *and* the
`main` branch, so a workflow on a fork or a side branch cannot assume it.

```bash
cat > /tmp/trust.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike":   { "token.actions.githubusercontent.com:sub": "repo:${GH_REPO}:ref:refs/heads/main" }
    }
  }]
}
JSON

aws iam create-role \
  --role-name JobTrailGitHubDeploy \
  --assume-role-policy-document file:///tmp/trust.json

# Only needs to describe the cluster; all real authority comes from the
# Kubernetes RBAC binding in 4c.
aws iam put-role-policy \
  --role-name JobTrailGitHubDeploy \
  --policy-name DescribeCluster \
  --policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Action":"eks:DescribeCluster","Resource":"*"}]
  }'
```

**4c — authorise it inside the cluster.** IAM alone is not enough; EKS keeps its
own mapping.

```bash
eksctl create iamidentitymapping \
  --cluster "$CLUSTER" --region "$AWS_REGION" \
  --arn "arn:aws:iam::${ACCOUNT_ID}:role/JobTrailGitHubDeploy" \
  --group system:masters \
  --username github-actions

# Verify
eksctl get iamidentitymapping --cluster "$CLUSTER" --region "$AWS_REGION"
```

> `system:masters` is cluster-admin. Fine to start; to tighten it later, bind a
> Role scoped to the `jobtrail` namespace instead and drop the group.

---

## 5. Create the secrets

Created imperatively so the values never exist in a file.

```bash
kubectl apply -f k8s/00-namespace.yaml

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

**The résumé**, mounted read-only at `/app/resume/resume.pdf`:

```bash
kubectl -n jobtrail create secret generic jobtrail-resume \
  --from-file=resume.pdf="$HOME/Documents/resume/Ranjeet_Java_AI_4Exp.pdf"
```

**Image pull** — only if the GHCR package is private:

```bash
kubectl -n jobtrail create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username='ranjeetyadav7348' \
  --docker-password='<GitHub PAT with read:packages>'
```

Making the package public instead (Package settings → Change visibility) is
simpler; if you do, delete the `imagePullSecrets` block from `k8s/20-app.yaml`.

---

## 6. Lock down who can reach it

`k8s/30-loadbalancer.yaml` ships with `loadBalancerSourceRanges` set to a
documentation-only address that matches nothing — it fails closed, so the first
deploy is unreachable until you edit it. That is deliberate: the app has **no
login**, and anyone who can reach it can read your application history and send
mail through your SMTP credentials.

```bash
curl -s ifconfig.me    # your public IP
```

Put `<that-ip>/32` in that file and commit.

---

## 7. GitHub repository configuration

Settings → Secrets and variables → Actions.

**Variables** (not secrets — these are not sensitive):

| Variable | Value |
|---|---|
| `AWS_REGION` | `ap-south-1` |
| `EKS_CLUSTER` | `jobtrail` |

**Secret:**

| Secret | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<account>:role/JobTrailGitHubDeploy` |

No AWS access keys. The role is assumed through OIDC and the credentials last
minutes.

Also create an Environment named `production` (Settings → Environments), which
the deploy job references. Add a required reviewer there for a manual gate.

---

## 8. Deploy

```bash
git add -A
git commit -m "Deploy to EKS via GitHub Actions"
git push origin main
```

Actions runs tests, builds and pushes a `linux/amd64` image tagged with the
commit SHA, applies the manifests, and waits for the rollout. If the pod never
becomes ready the job fails, rolls back to the previous revision, and prints pod
status, PVC state, events and logs.

First deploy takes ~10 minutes: EBS volume provisioning, Postgres init, Hibernate
schema creation, and NLB registration.

---

## 9. Finish the two chicken-and-egg settings

The load balancer hostname does not exist until after the first deploy.

```bash
kubectl -n jobtrail get svc jobtrail-lb \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Put it in `PUBLIC_BASE_URL` in `k8s/20-app.yaml` and push again. Until then the
app works but open-tracking pixels record nothing, because they are built from
that URL and the recipient's mail client has to resolve it.

Then index the résumé:

```bash
BASE="http://$(kubectl -n jobtrail get svc jobtrail-lb -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')"
curl -X POST "$BASE/api/knowledge/reindex"
curl "$BASE/api/decisions/status"
```

`status` should report non-zero `resumeChunks` and — unlike on your laptop —
`embeddingsReady: true`. The embedding model's native libraries ship for
`linux/amd64` but not `win-aarch64`, so **containerising is what switches hybrid
retrieval on**. Locally it degrades to keyword-only search.

Re-run the reindex after editing your CV. It is idempotent: unchanged content is
detected by hash and skipped without re-embedding.

---

## Operations

```bash
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER"

kubectl -n jobtrail logs -f deployment/jobtrail
kubectl -n jobtrail get pods,pvc,svc
kubectl -n jobtrail rollout restart deployment/jobtrail
kubectl -n jobtrail rollout undo deployment/jobtrail

# Deploy a specific commit
kubectl -n jobtrail set image deployment/jobtrail \
  jobtrail=ghcr.io/ranjeetyadav7348/jobtrailapplication:<sha>

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

**One replica, `Recreate` strategy — both deliberate.** The app runs its send
dispatcher, IMAP poller and follow-up scheduler in-process. A second replica
would poll the same queue and mailbox and send duplicate emails, from a tool
whose purpose is not annoying employers. `RollingUpdate` has the same problem for
a few seconds per deploy, which is why the strategy is `Recreate`. Scaling out
safely means putting the schedulers behind a database lock (ShedLock) first —
until then, do not raise `replicas`.

**The EBS volume is `Retain`.** Deleting the PVC or the namespace leaves the
volume behind rather than destroying your application history. The cost is that a
genuinely unwanted volume must be deleted by hand in the EC2 console.

**Backups are still worth it.** EBS survives node replacement; it does not
survive a bad migration or an accidental delete. `k8s/40-backup.yaml` needs
`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (scoped to one bucket) added to
`jobtrail-secrets` and `BACKUP_S3_URI` in the ConfigMap; the workflow applies it
only when those exist.

**Cost, roughly, per month:**

| Item | ~USD |
|---|---|
| EKS control plane | 73 |
| 2 × t3.medium | 60 |
| NLB | 16 |
| EBS 8 GiB gp3 + snapshots | 1 |
| **Total** | **~150** |

That is substantially more than the ~$32 a single-node k3s box would cost for
identical functionality. Worth it if the AWS experience is part of the point;
if not, `eksctl delete cluster --name "$CLUSTER"` and the k3s manifests are in
this repo's history. Scaling the node group to zero outside working hours cuts
the node share but not the control-plane fee.

**There is no authentication.** Keep `loadBalancerSourceRanges` restricted. The
real fix is an ALB Ingress with ACM for TLS and Cognito or OIDC in front — see
the note at the bottom of `k8s/30-loadbalancer.yaml`.
