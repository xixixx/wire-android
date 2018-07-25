/**
  * Wire
  * Copyright (C) 2018 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
package com.waz.zclient.appentry

import android.webkit.{WebView, WebViewClient}
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.model.UserId
import com.waz.sync.client.AuthenticationManager.Cookie
import com.waz.sync.client.LoginClient
import com.waz.utils.events.EventStream
import com.waz.utils.wrappers.URI
import com.waz.zclient.appentry.SSOWebViewWrapper._

import scala.concurrent.{Future, Promise}
import scala.util.Success


class SSOWebViewWrapper(webView: WebView, backendHost: String) {

  private var loginPromise = Promise[SSOResponse]()

  val onTitleChanged = EventStream[String]()
  val onUrlChanged = EventStream[String]()

  webView.getSettings.setJavaScriptEnabled(true)

  webView.setWebViewClient(new WebViewClient {
    override def onPageFinished(view: WebView, url: String): Unit = {
      onTitleChanged ! {
        val title = view.getTitle
        Option(URI.parse(title).getHost).filter(_.nonEmpty).getOrElse(title)
      }
      onUrlChanged ! url
      verbose(s"onPageFinished: $url")
    }

    override def shouldOverrideUrlLoading(view: WebView, url: LogTag): Boolean = {
      verbose(s"shouldOverrideUrlLoading: $url")
      parseURL(url).fold (false){ result =>
        loginPromise.tryComplete(Success(result))
        true
      }
    }
  })

  def loginWithCode(code: String): Future[SSOResponse] = {
    loginPromise.tryComplete(Success(Left(-1)))
    loginPromise = Promise[SSOResponse]()

    val url = URI.parse(s"$backendHost/${InitiateLoginPath(code)}")
      .buildUpon
      .appendQueryParameter("success_redirect", s"$ResponseSchema://success/?$CookieQuery=$$cookie&$UserIdQuery=$$userid")
      .appendQueryParameter("error_redirect", s"$ResponseSchema://error/?$FailureQuery=$$label")
      .build
      .toString

    webView.loadUrl(url)
    loginPromise.future
  }
}

object SSOWebViewWrapper {

  val ResponseSchema = "wire"
  val CookieQuery = "cookie"
  val UserIdQuery = "user"
  val FailureQuery = "failure"

  type SSOResponse = Either[Int, (Cookie, UserId)]
  def InitiateLoginPath(code: String) = s"sso/initiate-login/$code"

  val SSOErrors = Map(
    "UnknownIdP" -> 1,
    "Forbidden" -> 2,
    "BadSamlResponse" -> 3,
    "BadServerConfig" -> 4,
    "UnknownError" -> 5,
    "CustomServant" -> 6,
    "SparNotFound" -> 7,
    "SparNotInTeam" -> 8,
    "SparNotTeamOwner" -> 9,
    "SparNoRequestRefInResponse" -> 10,
    "SparCouldNotSubstituteSuccessURI" -> 11,
    "SparCouldNotSubstituteFailureURI" -> 12,
    "SparBadInitiateLoginQueryParams" -> 13,
    "SparBadUserName msg" -> 14,
    "SparNoBodyInBrigResponse" -> 15,
    "SparCouldNotParseBrigResponse" -> 16,
    "SparCouldNotRetrieveCookie	" -> 17,
    "SparCassandraError" -> 18,
    "SparNewIdPBadMetaUrl" -> 19,
    "SparNewIdPBadMetaSig" -> 20,
    "SparNewIdPBadReqUrl" -> 21,
    "SparNewIdPPubkeyMismatch" -> 22)

  def parseURL(url: String): Option[SSOResponse] = {
    val uri = URI.parse(url)

    if (uri.getScheme.equals(ResponseSchema)) {
      val cookie = Option(uri.getQueryParameter(CookieQuery))
      val userId = Option(uri.getQueryParameter(UserIdQuery))
      val failure = Option(uri.getQueryParameter(FailureQuery))

      (cookie, userId, failure) match {
        case (Some(_ @ LoginClient.CookieHeader(c)), Some(uId), _) => Some(Right(Cookie(c), UserId(uId)))
        case (_, _, Some(f)) => Some(Left(SSOErrors.getOrElse(f, 0)))
        case _ => None
      }
    } else None
  }
}
