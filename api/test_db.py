import os
from dotenv import load_dotenv
from pymongo import MongoClient

load_dotenv()

uri = os.getenv("MONGO_URI")
print("Connecting to MongoDB...")

client = MongoClient(uri)
db = client["video_ads"]

print("Collections:", db.list_collection_names())
print("✅ Connection successful!")
