# Cashpoint ATM Management System

## Run locally

```bash
./run.sh
```

Then open `http://localhost:3000`.

Admin login: account `admin`, PIN `9999`.

## Deploy to Render

1. Create a GitHub repository and upload this complete project folder.
2. Visit [Render](https://render.com/) and sign in with GitHub.
3. Click **New** → **Blueprint**.
4. Select the GitHub repository and click **Apply**.
5. Wait for the deployment to complete.
6. Open the generated `https://cashpoint-atm.onrender.com`-style URL from the Render dashboard.

The included `render.yaml` tells Render to use the included Dockerfile. The program automatically uses Render's `PORT` environment variable.

> Note: SQLite data is temporary on Render's free service and can be reset after a restart or redeploy. Do not use real banking data.
