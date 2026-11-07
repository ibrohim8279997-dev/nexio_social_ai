# Instagram setup

The app uses Meta's official **Instagram API with Instagram Login**. It never asks for your password
and does not use scraping, browser automation or unofficial endpoints.

## Before you start

- A **Business or Creator** Instagram account. Personal accounts cannot publish through the API.
- A free [Meta for Developers](https://developers.facebook.com/) app with the Instagram product.
- A storage bucket for temporary hosting (below).

## 1. Temporary hosting

Instagram does not accept file uploads from an app for images. It downloads them from a public
address, so each file must be reachable for a short time.

Create a bucket on any S3-compatible service, such as Cloudflare R2, AWS S3 or Backblaze B2. Make its
files publicly readable, and create an access key limited to that bucket. In **Instagram Manager >
Temporary media hosting**, enter the endpoint, region, bucket, public address and keys.

Each file is uploaded just before publishing and deleted right after. Keep the bucket dedicated to
this: anyone with a file's address can view it during that window.

## 2. Connect with an access token (simplest)

Use this for your own account:

1. In your Meta app, open **Instagram > API setup with Instagram login**.
2. Add your Instagram account and generate an access token.
3. In **Instagram Manager**, tap **Use an access token instead** and paste it.

Tokens from the dashboard last about 60 days. Because no expiry comes with a pasted token, the app
cannot renew it for you. Paste a new one when Instagram asks you to reconnect.

## 3. Connect with sign-in

This is the OAuth flow, and the app renews this token by itself.

1. Host [oauth-redirect.html](oauth-redirect.html) at any https address, for example GitHub Pages. It
   is a static page that passes the result back to the app, and it needs no server.
2. Add that address to **Valid OAuth Redirect URIs** in your Meta app.
3. In **Instagram Manager**, enter the Instagram app ID, the app secret and that address.
4. Tap **Sign in with Instagram** and approve on Instagram's page.

If the app does not reopen, copy the final address from the browser and paste it into **Address
shown after signing in**.

### The app secret

Instagram requires an app secret to exchange the sign-in code, and without a server it has to be on
the phone. It is encrypted with Android Keystore, but that is only suitable for your own Meta app and
your own phone. A commercial multi-user version must keep it on a backend.

## App review

While your Meta app is in development mode, only accounts you add to it as testers can connect. That
is enough for your own business. Serving other businesses needs Meta's App Review.

## Limits

- Instagram caps how many posts an account can publish through the API in 24 hours.
- A carousel counts as one post against that limit.
- Stories publish without a caption, as the API requires.
- Video must meet Instagram's format rules. Videos made in Video Studio use H.264 and AAC in MP4.
- Insights exist only for Business and Creator accounts, and only for posts published after you
  connected. They can appear with a delay.

## Carousels

Choose the type **Carousel** on a post, then pick **2 to 10 images** from the media library (or from
your phone) and put them in the order you want with the up and down buttons. Anything outside 2 to 10
cannot be approved or published.

- **Image children only in this version.** Instagram's official API also accepts video items in a
  carousel, but this version of the app does not offer them, and it refuses a carousel that contains
  a video with a clear message.
- Images follow the same rules as a single image post. Instagram lists JPEG as its supported image
  format; the app does not convert files, so use JPEG images.
- Changing the images, or their order, after you approved the post withdraws the approval, just like
  editing the caption. Approve again before it can be scheduled or published.
- A carousel counts as one post against Instagram's 24-hour publishing limit.

How it is published, all through the official content publishing API:

1. Every image is uploaded to your temporary hosting under its own address.
2. For each image the app creates a carousel item container (`is_carousel_item=true`) and waits until
   Instagram reports it as finished.
3. Only then does it create the carousel container (`media_type=CAROUSEL`, the items in your order and
   the caption) and waits until that is finished.
4. The state is saved as **Needs checking** just before the last call, then the carousel is published
   and the post's identifier is stored.

Progress of each image is saved as it happens, so if the phone restarts the app continues where it
stopped and does not upload or create finished images again. If any single image fails to upload or
Instagram reports an error or expiry for it, the whole post fails with Instagram's message and the
carousel is never created or published, so no partial carousel can appear on your profile. All
temporary files are deleted after every attempt, successful or not. A carousel that ended as "Needs
checking" is handled exactly like any other post below and is never published a second time blindly.

## When a post says "Needs checking"

The connection dropped during the final step, so the app cannot tell whether Instagram received it.
It does not send the post again. It asks Instagram for the post's status, and if that does not settle
it, you check your profile and choose **It was posted** or **It was not posted**.
