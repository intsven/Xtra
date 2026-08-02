import json
import logging
import sys
import asyncio
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
import requests
import firebase_admin
from firebase_admin import messaging
from twitchAPI.twitch import Twitch
from twitchAPI.eventsub.websocket import EventSubWebsocket
from twitchAPI.helper import first
from twitchAPI.type import AuthScope

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger(__name__)

CONFIG_PATH = "config.json"
FIREBASE_CRED_PATH = "xtra-aceec-firebase-adminsdk-fbsvc-e499c9c502.json"
API_PORT = 5000

server_state = {
    "fcm_token": None,
    "channel_ids": [],
    "client_id": None,
    "client_secret": None,
    "app_token": None,
    "twitch": None,
    "eventsub": None,
    "active_subscriptions": set(),
    "loop": None,
}


def load_config():
    with open(CONFIG_PATH, "r") as f:
        return json.load(f)


def save_config(config):
    with open(CONFIG_PATH, "w") as f:
        json.dump(config, f, indent=2)


def init_firebase():
    cred = firebase_admin.credentials.Certificate(FIREBASE_CRED_PATH)
    firebase_admin.initialize_app(cred)
    log.info("Firebase Admin SDK initialized")


def get_app_access_token(client_id: str, client_secret: str) -> str:
    resp = requests.post(
        "https://id.twitch.tv/oauth2/token",
        params={
            "client_id": client_id,
            "client_secret": client_secret,
            "grant_type": "client_credentials",
        },
    )
    resp.raise_for_status()
    return resp.json()["access_token"]


def get_streams_info(client_id: str, app_token: str, user_ids: list[str]) -> dict:
    headers = {"Client-ID": client_id, "Authorization": f"Bearer {app_token}"}
    params = [("user_id", uid) for uid in user_ids]
    resp = requests.get(
        "https://api.twitch.tv/helix/streams", headers=headers, params=params
    )
    resp.raise_for_status()
    data = resp.json().get("data", [])
    return {s["user_id"]: s for s in data}


def send_fcm_notification(fcm_token: str, data: dict):
    message = messaging.Message(
        data=data,
        notification=messaging.Notification(
            title=f"{data.get('channelName', 'Unknown')} is live!",
            body=data.get('title', ''),
        ),
        token=fcm_token,
        android=messaging.AndroidConfig(priority="high"),
    )
    response = messaging.send(message)
    log.info(f"FCM sent successfully for {data.get('channelName', 'unknown')}: {response}")


async def on_stream_online(data):
    broadcaster_id = data["broadcaster_user_id"]
    broadcaster_login = data["broadcaster_user_login"]
    broadcaster_name = data["broadcaster_user_name"]
    started_at = data.get("started_at", "")
    stream_id = data.get("id", "")

    log.info(f"Stream online: {broadcaster_name} ({broadcaster_login})")

    try:
        streams = get_streams_info(
            server_state["client_id"], server_state["app_token"], [broadcaster_id]
        )
        stream_info = streams.get(broadcaster_id, {})

        fcm_data = {
            "type": "live_notification",
            "channelId": broadcaster_id,
            "channelLogin": broadcaster_login,
            "channelName": broadcaster_name,
            "title": stream_info.get("title", ""),
            "gameName": stream_info.get("game_name", ""),
            "thumbnailURL": stream_info.get("thumbnail_url", "").replace(
                "{width}", "440"
            ).replace("{height}", "248"),
            "viewerCount": str(stream_info.get("viewer_count", 0)),
            "startedAt": started_at,
            "streamId": stream_id,
        }

        send_fcm_notification(server_state["fcm_token"], fcm_data)
    except Exception as e:
        log.error(f"Error processing stream online for {broadcaster_name}: {e}")


async def setup_eventsub(user_token: str):
    client_id = server_state.get("app_client_id") or server_state["client_id"]
    client_secret = server_state["client_secret"]

    twitch = Twitch(client_id, client_secret)
    twitch.auto_refresh_auth = False
    await twitch.set_user_authentication(user_token, scope=[AuthScope.USER_READ_FOLLOWS], validate=True)
    server_state["twitch"] = twitch

    eventsub = EventSubWebsocket(twitch)
    eventsub.start()
    server_state["eventsub"] = eventsub
    log.info("EventSub WebSocket connected")


async def subscribe_channels(channel_ids: list[str]):
    eventsub = server_state["eventsub"]
    if not eventsub:
        log.error("EventSub not initialized")
        return

    for ch_id in channel_ids:
        if ch_id not in server_state["active_subscriptions"]:
            try:
                await eventsub.listen_stream_online(ch_id, on_stream_online)
                server_state["active_subscriptions"].add(ch_id)
                log.info(f"Subscribed to stream.online for channel {ch_id}")
            except Exception as e:
                log.error(f"Failed to subscribe to channel {ch_id}: {e}")


async def handle_sync(channel_ids, fcm_token, user_token, client_id=None):
    log.info(f"handle_sync: {len(channel_ids)} channels, fcm_token={'yes' if fcm_token else 'no'}, user_token={'yes' if user_token else 'no'}, client_id={client_id}")
    if client_id:
        server_state["app_client_id"] = client_id

    if fcm_token:
        server_state["fcm_token"] = fcm_token
        config = load_config()
        config["fcm_token"] = fcm_token
        save_config(config)

    server_state["channel_ids"] = channel_ids
    config = load_config()
    config["followed_channel_ids"] = channel_ids
    save_config(config)

    if user_token and not server_state.get("eventsub"):
        try:
            await setup_eventsub(user_token)
        except Exception as e:
            log.error(f"Failed to setup EventSub: {e}")

    if server_state.get("eventsub") and channel_ids:
        await subscribe_channels(channel_ids)
    elif not server_state.get("eventsub"):
        log.error("EventSub not set up - no user token received or setup failed")


class ApiHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        log.info(f"API: {args[0]}")

    def do_POST(self):
        if self.path == "/api/channels":
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            try:
                payload = json.loads(body)
                channel_ids = payload.get("channel_ids", [])
                fcm_token = payload.get("fcm_token")
                user_token = payload.get("user_token")
                client_id = payload.get("client_id")

                asyncio.run_coroutine_threadsafe(
                    handle_sync(channel_ids, fcm_token, user_token, client_id),
                    server_state["loop"],
                )

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "ok", "channels": len(channel_ids)}).encode())
                log.info(f"Received {len(channel_ids)} channel IDs from app")
            except Exception as e:
                log.error(f"API error: {e}")
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode())
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        if self.path == "/api/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "subscriptions": len(server_state["active_subscriptions"]),
                "channels": list(server_state["active_subscriptions"]),
            }).encode())
        elif self.path == "/api/test":
            fcm_token = server_state.get("fcm_token")
            if not fcm_token:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "no fcm token"}).encode())
                return
            try:
                send_fcm_notification(fcm_token, {
                    "type": "live_notification",
                    "channelId": "56649026",
                    "channelLogin": "quin69",
                    "channelName": "Quin69",
                    "title": "Test notification",
                    "gameName": "Test",
                    "thumbnailURL": "",
                    "viewerCount": "0",
                    "startedAt": "2026-08-02T00:00:00Z",
                    "streamId": "test123",
                })
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "ok", "message": "test notification sent"}).encode())
            except Exception as e:
                log.error(f"Test FCM error: {e}")
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode())
        else:
            self.send_response(404)
            self.end_headers()


def run_api_server():
    server = HTTPServer(("0.0.0.0", API_PORT), ApiHandler)
    log.info(f"API server running on port {API_PORT}")
    server.serve_forever()


async def main_async():
    config = load_config()
    client_id = config["twitch_client_id"]
    client_secret = config["twitch_client_secret"]
    fcm_token = config.get("fcm_token", "")
    channel_ids = config.get("followed_channel_ids", [])

    server_state["fcm_token"] = fcm_token
    server_state["client_id"] = client_id
    server_state["client_secret"] = client_secret

    init_firebase()

    app_token = get_app_access_token(client_id, client_secret)
    server_state["app_token"] = app_token
    log.info("Got app access token")

    api_thread = threading.Thread(target=run_api_server, daemon=True)
    api_thread.start()

    if channel_ids:
        log.info(f"Channels in config: {channel_ids}")
    else:
        log.info("No channels in config. Waiting for app to send channel IDs + user token...")

    log.info(f"Server running on port {API_PORT}. Waiting for app connection.")
    log.info("Press Ctrl+C to stop.")

    try:
        while True:
            await asyncio.sleep(1)
    except (KeyboardInterrupt, asyncio.CancelledError):
        log.info("Shutting down...")
        if server_state.get("eventsub"):
            await server_state["eventsub"].stop()
        log.info("Stopped.")


def main():
    loop = asyncio.new_event_loop()
    server_state["loop"] = loop
    asyncio.set_event_loop(loop)
    try:
        loop.run_until_complete(main_async())
    finally:
        loop.close()


if __name__ == "__main__":
    main()
