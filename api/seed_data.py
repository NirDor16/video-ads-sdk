from dotenv import load_dotenv
from pymongo import MongoClient
import os
from datetime import datetime

load_dotenv()

client = MongoClient(os.getenv("MONGO_URI"))
db = client["video_ads"]

categories = db["categories"]
ads = db["ads"]

categories.delete_many({})
ads.delete_many({})

categories.insert_many([
    {"id": "TV",   "display_name": "TV",   "description": "TV-related video ads"},
    {"id": "CAR",  "display_name": "Car",  "description": "Car-related video ads"},
    {"id": "GAME", "display_name": "Game", "description": "Gaming-related video ads"},
])

now = datetime.utcnow().isoformat() + "Z"

ads.insert_many([
    # -------- TV --------
    {
        "ad_id": "ad_tv_001",
        "category_id": "TV",
        "title": "TV Promo 1",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
        "status": "active",
        "created_at": now
    },
    {
        "ad_id": "ad_tv_002",
        "category_id": "TV",
        "title": "TV Promo 2",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4",
        "status": "active",
        "created_at": now
    },
    {
        "ad_id": "ad_tv_003",
        "category_id": "TV",
        "title": "TV Promo 3",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
        "status": "active",
        "created_at": now
    },

    # -------- CAR --------
    {
        "ad_id": "ad_car_001",
        "category_id": "CAR",
        "title": "Car Ad 1",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4",
        "status": "active",
        "created_at": now
    },
    {
        "ad_id": "ad_car_002",
        "category_id": "CAR",
        "title": "Car Ad 2",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4",
        "status": "active",
        "created_at": now
    },
    

    # -------- GAME --------
    {
        "ad_id": "ad_game_001",
        "category_id": "GAME",
        "title": "Game Trailer 1",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        "status": "active",
        "created_at": now
    },
    {
        "ad_id": "ad_game_002",
        "category_id": "GAME",
        "title": "Game Trailer 2",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
        "status": "active",
        "created_at": now
    },
    {
        "ad_id": "ad_game_003",
        "category_id": "GAME",
        "title": "Game Trailer 3",
        "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
        "status": "active",
        "created_at": now
    },
])

print("✅ Seed data inserted successfully: 9 ads (3 per category)")
