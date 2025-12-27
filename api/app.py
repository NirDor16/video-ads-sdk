import os
import random
from datetime import datetime

from flask import Flask, jsonify, request, send_from_directory
from dotenv import load_dotenv

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
    cfg["x_delay_seconds"] = max(0, x_delay)

    return cfg


def create_app() -> Flask:
    app = Flask(__name__)

    # --------------------
    # Health check
    # --------------------
    @app.get("/health")
    def health():
        return jsonify({"status": "ok"}), 200

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
    # Serve ad
    # --------------------
    @app.get("/v1/serve")
    def serve_ad():
        app_id = request.args.get("app_id", "demo_app")
        mode = request.args.get("mode", "RANDOM").upper()  # RANDOM / MANUAL
        categories_param = request.args.get("categories", "")  # e.g. SPORT,TECH
        ad_id_param = request.args.get("ad_id")  # only for MANUAL

        # 1) Parse categories
        if categories_param.strip():
            requested_categories = [
                c.strip().upper()
                for c in categories_param.split(",")
                if c.strip()
            ]
        else:
            # If no categories provided → allow all
            requested_categories = [
                c["id"]
                for c in categories_collection.find({}, {"_id": 0, "id": 1})
            ]

        # 2) Query active ads from requested categories
        query = {
            "status": "active",
            "category_id": {"$in": requested_categories},
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
            chosen = next(
                (a for a in candidates if a.get("ad_id") == ad_id_param),
                None
            )
            if chosen:
                return jsonify({
                    "ad": chosen,
                    "mode": "MANUAL",
                    "app_id": app_id,
                    "requested_categories": requested_categories
                }), 200

            # fallback to random
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
    # Ads CRUD
    # --------------------
    @app.post("/v1/ads")
    def create_ad():
        data = request.get_json(silent=True) or {}

        required = ["ad_id", "category_id", "title", "video_url"]
        missing = [k for k in required if not str(data.get(k, "")).strip()]
        if missing:
            return jsonify({"error": "Missing required fields", "missing": missing}), 400

        ad_id = str(data["ad_id"]).strip()

        if ads_collection.find_one({"ad_id": ad_id}):
            return jsonify({"error": "ad_id already exists", "ad_id": ad_id}), 409

        doc = {
            "ad_id": ad_id,
            "category_id": str(data["category_id"]).strip().upper(),
            "title": str(data["title"]).strip(),
            "video_url": str(data["video_url"]).strip(),
            "status": str(data.get("status", "active")).strip().lower(),  # active / inactive
            "created_at": datetime.utcnow().isoformat() + "Z",
        }

        ads_collection.insert_one(doc)
        return jsonify({"ad": doc}), 201

    @app.get("/v1/ads")
    def list_ads():
        category_id = request.args.get("category_id")
        status = request.args.get("status")

        query = {}
        if category_id:
            query["category_id"] = category_id.strip().upper()
        if status:
            query["status"] = status.strip().lower()

        ads = list(ads_collection.find(query, {"_id": 0}))
        return jsonify({"ads": ads}), 200

    @app.get("/v1/ads/<ad_id>")
    def get_ad(ad_id: str):
        ad_id = ad_id.strip()
        ad = ads_collection.find_one({"ad_id": ad_id}, {"_id": 0})
        if not ad:
            return jsonify({"error": "Ad not found", "ad_id": ad_id}), 404
        return jsonify({"ad": ad}), 200

    @app.put("/v1/ads/<ad_id>")
    def update_ad(ad_id: str):
        data = request.get_json(silent=True) or {}
        ad_id = ad_id.strip()

        allowed = {"category_id", "title", "video_url", "status"}
        update = {k: v for k, v in data.items() if k in allowed and v is not None}

        if "category_id" in update:
            update["category_id"] = str(update["category_id"]).strip().upper()
        if "title" in update:
            update["title"] = str(update["title"]).strip()
        if "video_url" in update:
            update["video_url"] = str(update["video_url"]).strip()
        if "status" in update:
            update["status"] = str(update["status"]).strip().lower()

        if not update:
            return jsonify({
                "error": "No valid fields to update",
                "allowed": sorted(list(allowed))
            }), 400

        res = ads_collection.update_one({"ad_id": ad_id}, {"$set": update})
        if res.matched_count == 0:
            return jsonify({"error": "Ad not found", "ad_id": ad_id}), 404

        ad = ads_collection.find_one({"ad_id": ad_id}, {"_id": 0})
        return jsonify({"ad": ad}), 200

    @app.delete("/v1/ads/<ad_id>")
    def delete_ad(ad_id: str):
        ad_id = ad_id.strip()
        res = ads_collection.delete_one({"ad_id": ad_id})
        if res.deleted_count == 0:
            return jsonify({"error": "Ad not found", "ad_id": ad_id}), 404
        return jsonify({"deleted": True, "ad_id": ad_id}), 200

    # --------------------
    # Simple Admin Page (static)
    # --------------------
    @app.get("/admin")
    def admin_page():
        # expects file at: api/static/admin.html
        return send_from_directory("static", "admin.html")

    return app


if __name__ == "__main__":
    app = create_app()
    port = int(os.getenv("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=True)
