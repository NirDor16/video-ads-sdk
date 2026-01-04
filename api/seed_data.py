from dotenv import load_dotenv
from pymongo import MongoClient
import os
from datetime import datetime

load_dotenv()

client = MongoClient(os.getenv("MONGO_URI"))
db = client["video_ads"]

categories = db["categories"]
ads = db["ads"]
app_configs = db["app_configs"]

# clean
categories.delete_many({})
ads.delete_many({})
app_configs.delete_many({})

categories.insert_many([
    {"id": "TV",   "display_name": "TV",   "description": "TV-related video ads"},
    {"id": "CAR",  "display_name": "Car",  "description": "Car-related video ads"},
    {"id": "GAME", "display_name": "Game", "description": "Gaming-related video ads"},
])

now = datetime.utcnow().isoformat() + "Z"

TV_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
CAR_URL = "https://www.youtube.com/watch?v=9bZkp7q19f0"
GAME_URL = "https://www.youtube.com/watch?v=jfKfPfyJRdk"

APP_ID = "demo_app"

ads.insert_many([
    # -------- TV --------
    {
        "app_id": APP_ID,
        "ad_id": "ad_tv_001",
        "category_id": "TV",
        "title": "TV Promo 1",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
        "target_url": TV_URL,
        "status": "active",
        "created_at": now
    },
    {
        "app_id": APP_ID,
        "ad_id": "ad_tv_002",
        "category_id": "TV",
        "title": "TV Promo 2",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4",
        "target_url": TV_URL,
        "status": "active",
        "created_at": now
    },
    {
        "app_id": APP_ID,
        "ad_id": "ad_tv_003",
        "category_id": "TV",
        "title": "TV Promo 3",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
        "target_url": TV_URL,
        "status": "active",
        "created_at": now
    },

    # -------- CAR --------
    {
        "app_id": APP_ID,
        "ad_id": "ad_car_001",
        "category_id": "CAR",
        "title": "Car Ad 1",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4",
        "target_url": CAR_URL,
        "status": "active",
        "created_at": now
    },
    {
        "app_id": APP_ID,
        "ad_id": "ad_car_002",
        "category_id": "CAR",
        "title": "Car Ad 2",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4",
        "target_url": CAR_URL,
        "status": "active",
        "created_at": now
    },

    # -------- GAME --------
    {
        "app_id": APP_ID,
        "ad_id": "ad_game_001",
        "category_id": "GAME",
        "title": "Game Trailer 1",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        "target_url": GAME_URL,
        "status": "active",
        "created_at": now
    },
    {
        "app_id": APP_ID,
        "ad_id": "ad_game_002",
        "category_id": "GAME",
        "title": "Game Trailer 2",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
        "target_url": GAME_URL,
        "status": "active",
        "created_at": now
    },
    {
        "app_id": APP_ID,
        "ad_id": "ad_game_003",
        "category_id": "GAME",
        "title": "Game Trailer 3",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
        "target_url": GAME_URL,
        "status": "active",
        "created_at": now
    },
])

