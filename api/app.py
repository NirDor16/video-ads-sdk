import os
import random
import uuid
from datetime import datetime

from flask import Flask, jsonify, request, send_from_directory
from dotenv import load_dotenv
from werkzeug.utils import secure_filename

from db import categories_collection, ads_collection, app_configs_collection

load_dotenv()

DEFAULT_CONFIG = {
    "categories": ["TV", "CAR", "GAME"],
    # Trigger types: CLICKS / INTERVAL
    # CLICKS: show after N clicks
    # INTERVAL: show every N seconds
    "trigger": {"type": "CLICKS", "count": 15},
    "x_delay_seconds": 5
}

UPLOAD_DIR = os.getenv("UPLOAD_DIR", "uploads")
MAX_UPLOAD_MB = int(os.getenv("MAX_UPLOAD_MB", "150"))
ALLOWED_EXTENSIONS = {"mp4", "mov", "m4v", "webm"}


def allowed_file(filename: str) -> bool:
    if not filename or "." not in filename:
        return False
    ext = filename.rsplit(".", 1)[1].lower()
    return ext in ALLOWED_EXTENSIONS


def normalize_config(data: dict) -> dict:
    cfg = dict(DEFAULT_CONFIG)

    # categories
    cats = data.get("categories")
    if isinstance(cats, list):
        cleaned = [str(c).strip().upper() for c in cats if str(c).strip()]
        if cleaned:
            cfg["categories"] = cleaned

    # trigger
    trigger = data.get("trigger", {}) or {}
    ttype = str(trigger.get("type", cfg["trigger"]["type"])).strip().upper()

    if ttype == "INTERVAL":
        sec = int(trigger.get("seconds", 120))
        cfg["trigger"] = {"type": "INTERVAL", "seconds": max(10, sec)}
    else:
        cnt = int(trigger.get("count", 15))
        cfg["trigger"] = {"type": "CLICKS", "count": max(1, cnt)}

    # x delay
    x_delay = data.get("x_delay_seconds", 5)
    try:
        x_delay = int(x_delay)
    except Exception:
        x_delay = 5
    cfg["x_delay_seconds"] = min(30, max(5, x_delay))

    return cfg


def create_app() -> Flask:
    app = Flask(__name__)

    # uploads
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_MB * 1024 * 1024

    # --------------------
    # Health check
    # --------------------
    @app.get("/health")
    def health():
        return jsonify({"status": "ok"}), 200

    # --------------------
    # Serve uploaded media files
    # --------------------
    @app.get("/media/<path:filename>")
    def serve_media(filename: str):
        return send_from_directory(UPLOAD_DIR, filename, as_attachment=False)

    # --------------------
    # Get categories
    # --------------------
    @app.get("/v1/categories")
    def get_categories():
        categories = list(categories_collection.find({}, {"_id": 0}))
        return jsonify({"categories": categories}), 200

    # --------------------
    # App Config (per app_id)
    # --------------------
    @app.get("/v1/apps/<app_id>/config")
    def get_app_config(app_id: str):
        app_id = app_id.strip()
        doc = app_configs_collection.find_one({"app_id": app_id}, {"_id": 0})
        if not doc:
            return jsonify({"app_id": app_id, "config": DEFAULT_CONFIG}), 200
        return jsonify({"app_id": app_id, "config": doc.get("config", DEFAULT_CONFIG)}), 200

    @app.put("/v1/apps/<app_id>/config")
    def upsert_app_config(app_id: str):
        app_id = app_id.strip()
        data = request.get_json(silent=True) or {}

        # allow body either {"config": {...}} or direct {...}
        raw_cfg = data.get("config", data)
        cfg = normalize_config(raw_cfg)

        doc = {
            "app_id": app_id,
            "config": cfg,
            "updated_at": datetime.utcnow().isoformat() + "Z",
        }

        app_configs_collection.update_one(
            {"app_id": app_id},
            {"$set": doc},
            upsert=True
        )

        return jsonify({"app_id": app_id, "config": cfg}), 200

    # --------------------
    # Serve ad (FILTER BY app_id)
    # --------------------
    @app.get("/v1/serve")
    def serve_ad():
        app_id = request.args.get("app_id", "demo_app").strip()
        mode = request.args.get("mode", "RANDOM").upper()  # RANDOM / MANUAL
        categories_param = request.args.get("categories", "")  # e.g. TV,CAR
        ad_id_param = request.args.get("ad_id")  # only for MANUAL

        # 1) Parse categories
        if categories_param.strip():
            requested_categories = [
                c.strip().upper()
                for c in categories_param.split(",")
                if c.strip()
            ]
        else:
            requested_categories = [
                c["id"]
                for c in categories_collection.find({}, {"_id": 0, "id": 1})
            ]

        # 2) Query active ads from requested categories AND app_id
        query = {
            "status": "active",
            "category_id": {"$in": requested_categories},
            "app_id": app_id
        }

        candidates = list(ads_collection.find(query, {"_id": 0}))

        if not candidates:
            return jsonify({
                "ad": None,
                "reason": "NO_FILL",
                "mode": mode,
                "app_id": app_id,
                "requested_categories": requested_categories
            }), 200

        # 3) Manual mode
        if mode == "MANUAL" and ad_id_param:
            chosen = next((a for a in candidates if a.get("ad_id") == ad_id_param), None)
            if chosen:
                return jsonify({
                    "ad": chosen,
                    "mode": "MANUAL",
                    "app_id": app_id,
                    "requested_categories": requested_categories
                }), 200
            mode = "RANDOM"

        # 4) Random mode
        chosen = random.choice(candidates)
        return jsonify({
            "ad": chosen,
            "mode": mode,
            "app_id": app_id,
            "requested_categories": requested_categories
        }), 200

    # --------------------
    # App-scoped Ads CRUD
    # --------------------
    @app.get("/v1/apps/<app_id>/ads")
    def list_ads(app_id: str):
        app_id = app_id.strip()
        category_id = request.args.get("category_id")
        status = request.args.get("status")

        query = {"app_id": app_id}
        if category_id:
            query["category_id"] = category_id.strip().upper()
        if status:
            query["status"] = status.strip().lower()

        ads = list(ads_collection.find(query, {"_id": 0}))
        return jsonify({"app_id": app_id, "ads": ads}), 200

    @app.post("/v1/apps/<app_id>/ads")
    def create_ad(app_id: str):
        app_id = app_id.strip()
        data = request.get_json(silent=True) or {}

        required = ["ad_id", "category_id", "title", "video_url"]
        missing = [k for k in required if not str(data.get(k, "")).strip()]
        if missing:
            return jsonify({"error": "Missing required fields", "missing": missing}), 400

        ad_id = str(data["ad_id"]).strip()

        # ensure unique per app
        if ads_collection.find_one({"app_id": app_id, "ad_id": ad_id}):
            return jsonify({"error": "ad_id already exists for this app", "ad_id": ad_id, "app_id": app_id}), 409

        doc = {
            "app_id": app_id,
            "ad_id": ad_id,
            "category_id": str(data["category_id"]).strip().upper(),
            "title": str(data["title"]).strip(),
            "video_url": str(data["video_url"]).strip(),
            "target_url": str(data.get("target_url", "")).strip() or None,
            "status": str(data.get("status", "active")).strip().lower(),  # active / inactive
            "created_at": datetime.utcnow().isoformat() + "Z",
        }

        ads_collection.insert_one(doc)
        return jsonify({"ad": doc}), 201

    @app.get("/v1/apps/<app_id>/ads/<ad_id>")
    def get_ad(app_id: str, ad_id: str):
        app_id = app_id.strip()
        ad_id = ad_id.strip()
        ad = ads_collection.find_one({"app_id": app_id, "ad_id": ad_id}, {"_id": 0})
        if not ad:
            return jsonify({"error": "Ad not found", "app_id": app_id, "ad_id": ad_id}), 404
        return jsonify({"ad": ad}), 200

    @app.put("/v1/apps/<app_id>/ads/<ad_id>")
    def update_ad(app_id: str, ad_id: str):
        app_id = app_id.strip()
        ad_id = ad_id.strip()
        data = request.get_json(silent=True) or {}

        allowed = {"category_id", "title", "video_url", "status", "target_url"}
        update = {k: v for k, v in data.items() if k in allowed and v is not None}

        if "category_id" in update:
            update["category_id"] = str(update["category_id"]).strip().upper()
        if "title" in update:
            update["title"] = str(update["title"]).strip()
        if "video_url" in update:
            update["video_url"] = str(update["video_url"]).strip()
        if "status" in update:
            update["status"] = str(update["status"]).strip().lower()
        if "target_url" in update:
            update["target_url"] = str(update["target_url"]).strip() or None

        if not update:
            return jsonify({
                "error": "No valid fields to update",
                "allowed": sorted(list(allowed))
            }), 400

        res = ads_collection.update_one({"app_id": app_id, "ad_id": ad_id}, {"$set": update})
        if res.matched_count == 0:
            return jsonify({"error": "Ad not found", "app_id": app_id, "ad_id": ad_id}), 404

        ad = ads_collection.find_one({"app_id": app_id, "ad_id": ad_id}, {"_id": 0})
        return jsonify({"ad": ad}), 200

    @app.delete("/v1/apps/<app_id>/ads/<ad_id>")
    def delete_ad(app_id: str, ad_id: str):
        app_id = app_id.strip()
        ad_id = ad_id.strip()

        ad = ads_collection.find_one({"app_id": app_id, "ad_id": ad_id}, {"_id": 0})
        if not ad:
            return jsonify({"error": "Ad not found", "app_id": app_id, "ad_id": ad_id}), 404

        # delete uploaded file (if this ad was uploaded)
        uploaded_file = ad.get("uploaded_file")
        if uploaded_file:
            try:
                path = os.path.join(UPLOAD_DIR, uploaded_file)
                if os.path.exists(path):
                    os.remove(path)
            except Exception:
                pass

        ads_collection.delete_one({"app_id": app_id, "ad_id": ad_id})
        return jsonify({"deleted": True, "app_id": app_id, "ad_id": ad_id}), 200

    # --------------------
    # Upload custom video -> creates an Ad (app-scoped)
    # --------------------
    @app.post("/v1/apps/<app_id>/ads/upload")
    def upload_ad_video(app_id: str):
        app_id = app_id.strip()

        if "file" not in request.files:
            return jsonify({"error": "Missing file field (multipart 'file')"}), 400

        f = request.files["file"]
        if not f or not f.filename:
            return jsonify({"error": "No file selected"}), 400

        if not allowed_file(f.filename):
            return jsonify({"error": "Invalid file type", "allowed": sorted(list(ALLOWED_EXTENSIONS))}), 400

        category_id = (request.form.get("category_id") or "").strip().upper()
        title = (request.form.get("title") or "").strip()
        ad_id = (request.form.get("ad_id") or "").strip()
        target_url = (request.form.get("target_url") or "").strip() or None
        status = (request.form.get("status") or "active").strip().lower()

        if not category_id or not title:
            return jsonify({"error": "Missing required fields", "missing": ["category_id", "title"]}), 400

        if not ad_id:
            ad_id = f"ad_custom_{uuid.uuid4().hex[:10]}"

        # unique per app
        if ads_collection.find_one({"app_id": app_id, "ad_id": ad_id}):
            return jsonify({"error": "ad_id already exists for this app", "app_id": app_id, "ad_id": ad_id}), 409

        safe_original = secure_filename(f.filename)
        ext = safe_original.rsplit(".", 1)[1].lower()

        stored_name = f"{app_id}_{ad_id}_{uuid.uuid4().hex[:8]}.{ext}"
        save_path = os.path.join(UPLOAD_DIR, stored_name)
        f.save(save_path)

        base = request.host_url.rstrip("/")
        video_url = f"{base}/media/{stored_name}"

        doc = {
            "app_id": app_id,
            "ad_id": ad_id,
            "category_id": category_id,
            "title": title,
            "video_url": video_url,
            "target_url": target_url,
            "status": status,
            "created_at": datetime.utcnow().isoformat() + "Z",
            "uploaded_file": stored_name
        }

        ads_collection.insert_one(doc)
        return jsonify({"ad": doc}), 201

    # --------------------
    # Simple Admin Page (static)
    # --------------------
    @app.get("/admin")
    def admin_page():
        return send_from_directory("static", "admin.html")

    return app


if __name__ == "__main__":
    app = create_app()
    port = int(os.getenv("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=True)
