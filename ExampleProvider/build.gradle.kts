// use an integer for version numbers
version = 1

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "GoodShort - Drama Pendek & Lakon Indonesia"
    authors = listOf("CloudStream")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if beta plugin. Previously we had this as 0 but changed to 1.

    tvTypes = listOf(
        "TvSeries",
    )

    iconUrl =
        "https://www.goodshort.com/public/img/gs-icon.jpg"
}
