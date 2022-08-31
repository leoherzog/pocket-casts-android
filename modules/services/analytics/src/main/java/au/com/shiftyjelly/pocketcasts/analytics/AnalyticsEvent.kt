package au.com.shiftyjelly.pocketcasts.analytics

enum class AnalyticsEvent(val key: String) {

    // App lifecycle events
    APPLICATION_INSTALLED("application_installed"),
    APPLICATION_UPDATED("application_updated"),
    APPLICATION_OPENED("application_opened"),
    APPLICATION_CLOSED("application_closed"),

    // User lifecycle events
    USER_SIGNED_IN("user_signed_in"),
    USER_SIGNIN_FAILED("user_signin_failed"),
    USER_ACCOUNT_DELETED("user_account_deleted"),
    USER_PASSWORD_UPDATED("user_password_updated"),
    USER_EMAIL_UPDATED("user_email_updated"),
    USER_PASSWORD_RESET("user_password_reset"),
    USER_ACCOUNT_CREATED("user_account_created"),
    USER_ACCOUNT_CREATION_FAILED("user_account_creation_failed"),
    USER_SIGNED_OUT("user_signed_out")
}