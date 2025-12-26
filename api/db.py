import os
from pymongo import MongoClient

client = MongoClient(os.getenv("MONGO_URI"))
db = client["video_ads"]

categories_collection = db["categories"]
ads_collection = db["ads"]
events_collection = db["events"]
