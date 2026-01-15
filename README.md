# Video Ads SDK

A complete video advertising platform consisting of a cloud-hosted REST API, a publicly published Android SDK, an example Android application, and a web-based administration portal for managing video ads.

The system allows developers to easily integrate video advertisements into Android applications using a simple SDK, while managing ads remotely through a centralized backend service.

---

## ✨ Features

- Cloud-hosted REST API (Flask)
- MongoDB Atlas cloud database
- Android SDK written in Kotlin
- SDK published as a public library via **JitPack**
- Automatic ad triggering (click-based or time-based)
- Built-in video ad player
- Web-based Admin Portal for managing ads
- App-level isolation using `app_id`
- Example Android application demonstrating SDK usage
- URL-based video ads (MP4)

---


<img width="397" height="552" alt="image" src="https://github.com/user-attachments/assets/b87c38ee-8c4e-4c24-a0f4-849cba994fcf" />



---

## 🌐 API Service

The backend API service is responsible for:
- Managing video ads (CRUD)
- Serving ads to client applications
- Storing data in MongoDB Atlas
- Providing a web-based admin interface

### Technologies
- Python + Flask
- MongoDB Atlas
- Deployed to cloud (Render)

### Base URL
https://video-ads-sdk.onrender.com

### Admin Portal : https://video-ads-sdk.onrender.com/admin
<img width="1889" height="471" alt="image" src="https://github.com/user-attachments/assets/930c7e5c-6657-46d6-a549-c5bd67f81d64" />


### Environment Variables
The following environment variable is required:

- `MONGO_URI` – MongoDB Atlas connection string

### Deployment Flow
1. Push changes to the GitHub repository.
2. Render automatically triggers a new build (or manual deploy).
3. Verify deployment using `/health`.

---

## 🔌 API Endpoints (Main)

- `GET /v1/serve` – Serve a video ad to the SDK
- `GET /v1/apps/{app_id}/ads` – List ads for an application
- `POST /v1/apps/{app_id}/ads` – Create a new ad
- `DELETE /v1/apps/{app_id}/ads/{ad_id}` – Delete an ad
- `GET /admin` – Web admin portal

---

## 📱 Android SDK

The Android SDK provides a simple and automatic way to integrate video ads into Android applications.

### Distribution
The SDK is published as a **public library via JitPack**.

### Integration (Gradle)

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.<your-username>:video-ads-sdk:1.0.0"
}

AdsSdk.init(
    context = applicationContext,
    baseUrl = "https://video-ads-sdk.onrender.com/",
    appId = "demo_app"
)
```

Ad Configuration
The SDK allows developers to configure how and when ads are shown using
the setPreferences method.
```Click-based Ads
lifecycleScope.launch {
    AdsSdk.setPreferences(
        categories = listOf("TV", "CAR", "GAME"),
        triggerType = "CLICKS",
        clicksCount = 5,
        xDelaySeconds = 5
    )
}
```
Explanation:

- categories – Allowed ad categories

- triggerType = "CLICKS" – Show ad after a number of clicks

- clicksCount – Number of clicks before showing an ad

- xDelaySeconds – Delay before showing the close (X) button
  
```Interval-based Ads
lifecycleScope.launch {
    AdsSdk.setPreferences(
        categories = listOf("TV", "CAR", "GAME"),
        triggerType = "INTERVAL",
        intervalSeconds = 30,
        xDelaySeconds = 5
    )
}
```

Explanation:

- triggerType = "INTERVAL" – Time-based ad display

- intervalSeconds – Time between ads (in seconds)

- xDelaySeconds – Delay before allowing the user to close the ad

🎮 Example Android Application

- An example Android application is included in the repository to demonstrate:

- SDK initialization

- Ad configuration

- Automatic ad triggering

- Video playback behavior

- Best practices for integration

- The application is written in Kotlin and serves as a reference implementation.

  
🛠 Admin Portal

- The Admin Portal allows managing video ads through a web browser.

- Capabilities

- View existing ads

- Add new ads using a video URL (MP4)

- Delete ads

- Manage ads per application using app_id

📄 Documentation

- Additional documentation is available in the docs/ directory and includes:

- API reference

- SDK usage examples

- System diagrams

- Setup instructions

- The documentation is published via GitHub Pages.

📜 License

Copyright 2026 Nir Dor

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

















