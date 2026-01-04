import os
import random
from datetime import datetime

from flask import Flask, jsonify, request, send_from_directory
from dotenv import load_dotenv

from db import categories_collection, ads_collection, app_configs_collection

load_dotenv()

DEFAULT_CONFIG = {
    "categories": ["TV", "CAR", "GAME"],
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
    try:
        x_delay = int(data.get("x_delay_seconds", 5))
    except Exception:
        x_delay = 5
    cfg["x_delay_seconds"] = min(30, max(5, x_delay))

    return cfg


def create_app() -> Flask:
    app = Flask(__name__)

    # --------------------
    # Health
    # --------------------
    @app.get("/health")
    def health():
        return jsonify({"status": "ok"}), 200

    # --------------------
    # Categories
    # --------------------
    @app.get("/v1/categories")
    def get_categories():
        categories = list(categories_collection.find({}, {"_id": 0}))
        return jsonify({"categories": categories}), 200

    # --------------------
    # App Config (unchanged – SDK side)
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

        raw_cfg = data.get("config", data)
        cfg = normalize_config(raw_cfg)

        app_configs_collection.update_one(
            {"app_id": app_id},
            {"$set": {
                "app_id": app_id,
                "config": cfg,
                "updated_at": datetime.utcnow().isoformat() + "Z"
            }},
            upsert=True
        )

        return jsonify({"app_id": app_id, "config": cfg}), 200

    # --------------------
    # Serve Ad (SDK endpoint)
    # --------------------
    @app.get("/v1/serve")
    def serve_ad():
        app_id = request.args.get("app_id", "").strip()
        mode = request.args.get("mode", "RANDOM").upper()
        categories_param = request.args.get("categories", "")
        ad_id_param = request.args.get("ad_id")

        if not app_id:
            return jsonify({"error": "Missing app_id"}), 400

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

        query = {
            "app_id": app_id,
            "status": "active",
            "category_id": {"$in": requested_categories}
        }

        candidates = list(ads_collection.find(query, {"_id": 0}))

        if not candidates:
            return jsonify({
                "ad": None,
                "reason": "NO_FILL",
                "app_id": app_id,
                "requested_categories": requested_categories
            }), 200

        if mode == "MANUAL" and ad_id_param:
            chosen = next((a for a in candidates if a["ad_id"] == ad_id_param), None)
            if chosen:
                return jsonify({"ad": chosen, "mode": "MANUAL"}), 200

        chosen = random.choice(candidates)
        return jsonify({"ad": chosen, "mode": "RANDOM"}), 200

    # --------------------
    # Ads CRUD (URL-only)
    # --------------------
    @app.get("/v1/apps/<app_id>/ads")
    def list_ads(app_id: str):
        app_id = app_id.strip()
        ads = list(ads_collection.find({"app_id": app_id}, {"_id": 0}))
        return jsonify({"app_id": app_id, "ads": ads}), 200

    @app.post("/v1/apps/<app_id>/ads")
    def create_ad(app_id: str):
        app_id = app_id.strip()
        data = request.get_json(silent=True) or {}

        required = ["category_id", "title", "video_url"]
        missing = [k for k in required if not str(data.get(k, "")).strip()]
        if missing:
            return jsonify({"error": "Missing required fields", "missing": missing}), 400

        ad_id = str(data.get("ad_id") or "").strip()
        if not ad_id:
            ad_id = f"ad_{int(datetime.utcnow().timestamp())}"

        if ads_collection.find_one({"app_id": app_id, "ad_id": ad_id}):
            return jsonify({"error": "ad_id already exists", "ad_id": ad_id}), 409

        doc = {
            "app_id": app_id,
            "ad_id": ad_id,
            "category_id": str(data["category_id"]).strip().upper(),
            "title": str(data["title"]).strip(),
            "video_url": str(data["video_url"]).strip(),
            "target_url": str(data.get("target_url", "")).strip() or None,
            "status": "active",
            "created_at": datetime.utcnow().isoformat() + "Z"
        }

        ads_collection.insert_one(doc)
        return jsonify({"ad": doc}), 201

    @app.delete("/v1/apps/<app_id>/ads/<ad_id>")
    def delete_ad(app_id: str, ad_id: str):
        app_id = app_id.strip()
        ad_id = ad_id.strip()

        res = ads_collection.delete_one({"app_id": app_id, "ad_id": ad_id})
        if res.deleted_count == 0:
            return jsonify({"error": "Ad not found"}), 404

        return jsonify({"deleted": True, "ad_id": ad_id}), 200

    # --------------------
    # Admin UI
    # --------------------
    @app.get("/admin")
    def admin_page():
        return send_from_directory("static", "admin.html")

    return app


if __name__ == "__main__":
    app = create_app()
    port = int(os.getenv("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=True)
