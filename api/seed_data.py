from dotenv import load_dotenv
from pymongo import MongoClient
import os
from datetime import datetime

load_dotenv()

client = MongoClient(os.getenv("MONGO_URI"))
db = client["video_ads"]

categories = db["categories"]
ads = db["ads"]

# ניקוי (כדי שלא ייכנס כפול)
categories.delete_many({})
ads.delete_many({})

# הכנסת קטגוריות
categories.insert_many([
    {
        "id": "SPORT",
        "display_name": "Sport",
        "description": "Sports-related video ads"
    },
    {
        "id": "FOOD",
        "display_name": "Food",
        "description": "Food & restaurants video ads"
    },
    {
        "id": "TECH",
        "display_name": "Tech",
        "description": "Technology & apps video ads"
    }
])

# הכנסת פרסומת לדוגמה
ads.insert_one({
    "ad_id": "ad_sport_001",
    "category_id": "SPORT",
    "title": "Sport Shoes Ad",
    "video_url": "https://samplelib.com/lib/preview/mp4/sample-5s.mp4",
    "status": "active",
    "created_at": datetime.utcnow()
})

print("✅ Seed data inserted successfully!")
