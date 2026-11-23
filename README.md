# MedLocker

MedLocker is a medical document vault. Patients can store PDFs and photos, manage their profile, and give an approved doctor temporary access to a specific PDF. A patient generates a six-digit PIN, and the signed-in doctor uses it to open the report within 15 minutes.

## How MedLocker works

1. **Upload.** The patient uploads a PDF or adds a JPEG or PNG to their gallery. Spring Boot checks the upload and encrypts each file with a fresh AES-256-GCM data key. MinIO stores the encrypted file. PostgreSQL stores its metadata, IV, and wrapped data key.
2. **Manage.** The patient can open, replace, delete, or print their PDFs, and view photos in their gallery. Account and profile details are stored in PostgreSQL.
3. **Share.** The patient requests a six-digit PIN for one PDF. MedLocker stores a keyed hash of the PIN and expires it after 15 minutes. Replacing or deleting the PDF revokes its existing PINs for future requests.
4. **View.** An authenticated doctor enters the PIN. The server validates the PIN, decrypts the PDF, and returns it with caching disabled. PDF.js renders it in the clinic portal. A doctor can also save a copy with **Download PDF**.

The master encryption key is supplied through server configuration. The server can decrypt authorized files. Downloads and prints create copies outside MedLocker's control.

## Project structure

| Path | Purpose |
| --- | --- |
| `frontend/index.html` | Patient sign-in, vault, photo gallery, and profile |
| `frontend/viewer.html` | Doctor sign-in, PIN entry, and PDF viewer |
| `frontend/css/` and `frontend/js/` | Shared styles and vanilla JavaScript for the portals |
| `backend/src/main/java/com/medlocker/` | Spring controllers, access rules, encryption, and persistence |
| `backend/src/main/resources/application.yml` | Spring Boot configuration |
| `backend/pom.xml` | Java 21 Maven build, including frontend assets |
| `docker-compose.yml` | Local PostgreSQL 16 and MinIO |
| `docker-compose.production.yml` and `docker/` | Container images and the Caddy HTTPS deployment |
| `.env.example` and `.env.production.example` | Environment variable templates |

Spring Boot serves both portals and the `/api/v1` endpoints from the same origin. Within the backend, `controller/` defines the HTTP endpoints, `service/` handles application logic, `repository/` accesses PostgreSQL, and `model/` defines stored records.

## Run locally

Install JDK 21, Maven, and Docker with Compose. From the project root, copy `.env.example` to `.env`, set the PostgreSQL and MinIO passwords, and generate three independent random 32-byte Base64 values for `MEDLOCKER_MASTER_KEY`, `MEDLOCKER_JWT_SECRET`, and `MEDLOCKER_PIN_PEPPER`.

```sh
docker compose up -d --build --wait
mvn -f backend/pom.xml clean compile
mvn -f backend/pom.xml spring-boot:run
```

Open the [patient portal](http://127.0.0.1:8080/) or the [doctor portal](http://127.0.0.1:8080/viewer.html). Set `MEDLOCKER_DOCTOR_INVITE_CODE` to a private random code of 24 to 128 characters to enable doctor registration. The browser loads PDF.js from jsDelivr. Camera capture requires browser permission on localhost or HTTPS.

Email password recovery requires SMTP settings. Google, Apple, and Microsoft sign-in require provider credentials and registered callback URLs under `/login/oauth2/code/{provider}`. Patients and doctors can connect a provider after signing in with their MedLocker password.

For a server deployment, fill in `.env.production` using the production template, point `MEDLOCKER_DOMAIN` at the server, and allow TCP ports 80 and 443 for Caddy.

```sh
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build --wait
```

Keep environment secrets out of Git. Back up PostgreSQL and MinIO together, and protect the matching master key so backed-up files can be decrypted after a restore.

## Host a demo on free tiers

The Render Blueprint runs the Java app on Render. Use a free Neon PostgreSQL project for persistent metadata and a private Backblaze B2 bucket for encrypted files. The app and database can sleep on their free plans, so the first request may take longer. Use sample records on this deployment.

1. Create a [Neon Free](https://neon.com/pricing) PostgreSQL project. From **Connect**, note the database hostname, database name, role, and password.
2. Create a [Backblaze B2 account](https://www.backblaze.com/sign-up/s3) and a private bucket. Create a read/write application key restricted to that bucket, with **Allow List All Bucket Names** enabled for the startup bucket check. Set the bucket lifecycle to **Keep only the last version** so hidden versions are cleaned up. [Application keys](https://www.backblaze.com/docs/cloud-storage-s3-compatible-app-keys) · [Lifecycle rules](https://www.backblaze.com/docs/cloud-storage-lifecycle-rules)
3. In Render, choose **New → Blueprint** and select this GitHub repository. Enter the values below in Render's dashboard. [Render Blueprint setup](https://render.com/docs/infrastructure-as-code)

| Render variable | Value |
| --- | --- |
| `MEDLOCKER_DATABASE_URL` | `jdbc:postgresql://NEON_HOST:5432/NEON_DATABASE?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` |
| `POSTGRES_USER` | Neon role |
| `POSTGRES_PASSWORD` | Neon role password |
| `MEDLOCKER_MINIO_ENDPOINT` | B2 S3 endpoint including `https://`, without the bucket name |
| `MEDLOCKER_MINIO_REGION` | The region shown in the B2 endpoint, such as `us-west-004` |
| `MEDLOCKER_MINIO_BUCKET` | Private B2 bucket name |
| `MINIO_ROOT_USER` | B2 application key ID |
| `MINIO_ROOT_PASSWORD` | B2 application key |

Render generates the encryption key, JWT secret, PIN pepper, and doctor invite code on first setup. Preserve the master key if you keep the database and bucket. Check the HTTPS link assigned by Render and test patient registration, a PDF upload, and doctor PIN access. Free Render web services block the SMTP ports used by the current email reset feature. Regular password sign-in works without SMTP. [Render free-tier limits](https://render.com/docs/free)
