package dev.marketlab.theories

import dev.marketlab.theory.Citation
import dev.marketlab.theory.EvidenceRole
import dev.marketlab.theory.PublicationKind

internal object AcademicReferences {
    val GUEGAN_RENAULT_SOCIAL_SENTIMENT = Citation(
        key = "guegan-renault-2021-social-sentiment",
        authors = listOf("Dominique Guégan", "Thomas Renault"),
        year = 2021,
        title = "Does investor sentiment on social media provide robust information for Bitcoin returns predictability?",
        venue = "Finance Research Letters 38, 101494",
        locator = "https://doi.org/10.1016/j.frl.2020.101494",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance =
            "Reports short-lived intraday Bitcoin return predictability from StockTwits sentiment, " +
                "but no economic profit after realistic costs.",
    )

    val SUARDI_RASEL_LIU_TWEET_SENTIMENT = Citation(
        key = "suardi-rasel-liu-2022-tweet-sentiment",
        authors = listOf("Sandy Suardi", "Atiqur Rahman Rasel", "Bin Liu"),
        year = 2022,
        title = "On the predictive power of tweet sentiments and attention on bitcoin",
        venue = "International Review of Economics & Finance 79, 289–301",
        locator = "https://doi.org/10.1016/j.iref.2022.02.017",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance =
            "Links sentiment dispersion to Bitcoin volatility and attention to trading volume, " +
                "while finding no broad attention-return effect.",
    )

    val MAITRE_PUGACHYOV_WEIGERT_ATTENTION = Citation(
        key = "maitre-pugachyov-weigert-2025-attention",
        authors = listOf("Arnaud T. Maître", "Nikolay Pugachyov", "Florian Weigert"),
        year = 2025,
        title = "Social media-based attention and the cross-section of cryptocurrency returns",
        venue = "Journal of Banking & Finance 178, 107518",
        locator = "https://doi.org/10.1016/j.jbankfin.2025.107518",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance =
            "Reports positive one-day-ahead cross-sectional cryptocurrency returns following " +
                "abnormal investor attention.",
    )

    val FAMA_EFFICIENT_MARKETS = Citation(
        key = "fama-1970-efficient-markets",
        authors = listOf("Eugene F. Fama"),
        year = 1970,
        title = "Efficient Capital Markets: A Review of Theory and Empirical Work",
        venue = "The Journal of Finance 25(2), 383–417",
        locator = "https://doi.org/10.2307/2325486",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.METHODOLOGY,
        relevance = "Defines weak-form efficiency and the martingale-style null against predictable returns.",
    )

    val WELCH_GOYAL_FORECAST_BASELINES = Citation(
        key = "welch-goyal-2008-forecasting",
        authors = listOf("Ivo Welch", "Amit Goyal"),
        year = 2008,
        title = "A Comprehensive Look at The Empirical Performance of Equity Premium Prediction",
        venue = "The Review of Financial Studies 21(4), 1455–1508",
        locator = "https://doi.org/10.1093/rfs/hhm014",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.METHODOLOGY,
        relevance = "Motivates strict historical-mean out-of-sample forecast comparisons.",
    )

    val CORSI_HAR_RV = Citation(
        key = "corsi-2009-har-rv",
        authors = listOf("Fulvio Corsi"),
        year = 2009,
        title = "A Simple Approximate Long-Memory Model of Realized Volatility",
        venue = "Journal of Financial Econometrics 7(2), 174–196",
        locator = "https://doi.org/10.1093/jjfinec/nbp001",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Introduces the daily, weekly, and monthly HAR-RV cascade and reports useful forecasts.",
    )

    val PATTON_VOLATILITY_PROXY_LOSS = Citation(
        key = "patton-2011-volatility-proxies",
        authors = listOf("Andrew J. Patton"),
        year = 2011,
        title = "Volatility Forecast Comparison Using Imperfect Volatility Proxies",
        venue = "Journal of Econometrics 160(1), 246–256",
        locator = "https://doi.org/10.1016/j.jeconom.2010.03.034",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.METHODOLOGY,
        relevance =
            "Establishes QLIKE as a robust loss for ranking variance forecasts when realized variance is a noisy proxy.",
    )

    val BRAUNEIS_SAHINER_CRYPTO_HAR = Citation(
        key = "brauneis-sahiner-2026-crypto-har",
        authors = listOf("Alexander Brauneis", "Mehmet Sahiner"),
        year = 2026,
        title = "Crypto Volatility Forecasting: Mounting a HAR, Sentiment, and Machine Learning Horserace",
        venue = "Asia-Pacific Financial Markets 33(1), 379–411",
        locator = "https://doi.org/10.1007/s10690-024-09510-6",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance =
            "Applies HAR realized-volatility forecasts to crypto assets and reports BTC-specific QLIKE comparisons.",
    )

    val HANSEN_KIM_KIMBROUGH_CRYPTO_PERIODICITY = Citation(
        key = "hansen-kim-kimbrough-2024",
        authors = listOf("Peter Reinhard Hansen", "Chan Kim", "Wade Kimbrough"),
        year = 2024,
        title = "Periodicity in Cryptocurrency Volatility and Liquidity",
        venue = "Journal of Financial Econometrics 22(1), 224–251",
        locator = "https://doi.org/10.1093/jjfinec/nbac034",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance =
            "Documents hour-of-day periodicity in BTC and ETH volatility and reports improved " +
                "out-of-sample volatility forecasts when periodicity is modeled.",
    )

    val MARTENS_HAR_LIMITATION = Citation(
        key = "martens-van-dijk-de-pooter-2009",
        authors = listOf("Martin Martens", "Dick van Dijk", "Michiel de Pooter"),
        year = 2009,
        title = "Forecasting S&P 500 Volatility: Long Memory, Level Shifts, Leverage Effects, Day-of-the-Week Seasonality, and Macroeconomic Announcements",
        venue = "International Journal of Forecasting 25(2), 282–303",
        locator = "https://doi.org/10.1016/j.ijforecast.2009.01.010",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.CONTRADICTORY,
        relevance = "Finds flexible high-order autoregressions can outperform parsimonious HAR at longer horizons.",
    )

    val HE_PERPETUAL_FUTURES = Citation(
        key = "he-manela-ross-von-wachter-2022",
        authors = listOf("Songrun He", "Asaf Manela", "Omri Ross", "Victor von Wachter"),
        year = 2022,
        title = "Fundamentals of Perpetual Futures",
        venue = "SSRN Working Paper 4301150",
        locator = "https://doi.org/10.2139/ssrn.4301150",
        kind = PublicationKind.WORKING_PAPER,
        role = EvidenceRole.SUPPORTING,
        relevance = "Derives no-arbitrage perpetual prices and studies funding/basis deviations under trading costs.",
    )

    val SCHMELING_CRYPTO_CARRY = Citation(
        key = "schmeling-schrimpf-todorov-2026",
        authors = listOf("Maik Schmeling", "Andreas Schrimpf", "Karamfil Todorov"),
        year = 2026,
        title = "Crypto Carry",
        venue = "Management Science, Articles in Advance",
        locator = "https://doi.org/10.1287/mnsc.2024.05069",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Documents large, time-varying crypto carry and links it to leveraged demand and arbitrage frictions.",
    )

    val MOSKOWITZ_TIME_SERIES_MOMENTUM = Citation(
        key = "moskowitz-ooi-pedersen-2012",
        authors = listOf("Tobias J. Moskowitz", "Yao Hua Ooi", "Lasse Heje Pedersen"),
        year = 2012,
        title = "Time Series Momentum",
        venue = "Journal of Financial Economics 104(2), 228–250",
        locator = "https://doi.org/10.1016/j.jfineco.2011.11.003",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Reports positive own-return predictability and trend strategy performance across futures.",
    )

    val LIU_TSYVINSKI_CRYPTOCURRENCY_RETURNS = Citation(
        key = "liu-tsyvinski-2021",
        authors = listOf("Yukun Liu", "Aleh Tsyvinski"),
        year = 2021,
        title = "Risks and Returns of Cryptocurrency",
        venue = "The Review of Financial Studies 34(6), 2689–2727",
        locator = "https://doi.org/10.1093/rfs/hhaa113",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Reports crypto-specific time-series momentum alongside other cryptocurrency return predictors.",
    )

    val HUANG_TIME_SERIES_MOMENTUM_CRITIQUE = Citation(
        key = "huang-li-wang-2020",
        authors = listOf("Dashan Huang", "Jun Li", "Liang Wang"),
        year = 2020,
        title = "Time Series Momentum: Is It There?",
        venue = "Journal of Financial Economics 135(3), 774–794",
        locator = "https://doi.org/10.1016/j.jfineco.2019.08.004",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.CONTRADICTORY,
        relevance = "Finds weak asset-level predictability and performance similar to a historical-mean strategy.",
    )

    val HUANG_TIME_SERIES_MOMENTUM_CRITIQUE_CORRECTED = Citation(
        key = "huang-li-wang-zhou-2020",
        authors = listOf("Dashan Huang", "Jiangyuan Li", "Liyao Wang", "Guofu Zhou"),
        year = 2020,
        title = "Time Series Momentum: Is It There?",
        venue = "Journal of Financial Economics 135(3), 774–794",
        locator = "https://doi.org/10.1016/j.jfineco.2019.08.004",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.CONTRADICTORY,
        relevance =
            "Finds weak asset-level predictability and performance similar to a historical-mean strategy; " +
                "this corrected record is used by adaptations without rewriting frozen parent plans.",
    )

    val CONT_ORDER_FLOW_IMBALANCE = Citation(
        key = "cont-kukanov-stoikov-2014",
        authors = listOf("Rama Cont", "Arseniy Kukanov", "Sasha Stoikov"),
        year = 2014,
        title = "The Price Impact of Order Book Events",
        venue = "Journal of Financial Econometrics 12(1), 47–88",
        locator = "https://doi.org/10.1093/jjfinec/nbt003",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Shows contemporaneous price changes are robustly related to order-flow imbalance.",
    )

    val GOULD_QUEUE_IMBALANCE = Citation(
        key = "gould-bonart-2016",
        authors = listOf("Martin D. Gould", "Julius Bonart"),
        year = 2016,
        title = "Queue Imbalance as a One-Tick-Ahead Price Predictor in a Limit Order Book",
        venue = "Market Microstructure and Liquidity 2(2)",
        locator = "https://doi.org/10.1142/S2382626616500065",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Tests queue imbalance against a null model out of sample and documents tick-size heterogeneity.",
    )

    val NAGEL_EVAPORATING_LIQUIDITY = Citation(
        key = "nagel-2012-evaporating-liquidity",
        authors = listOf("Stefan Nagel"),
        year = 2012,
        title = "Evaporating Liquidity",
        venue = "The Review of Financial Studies 25(7), 2005–2039",
        locator = "https://doi.org/10.1093/rfs/hhs066",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Interprets short-term reversal as liquidity provision and shows strong liquidity-state dependence.",
    )

    val WEN_CRYPTO_INTRADAY_REVERSAL = Citation(
        key = "wen-bouri-xu-zhao-2022",
        authors = listOf("Zhuzhu Wen", "Elie Bouri", "Yahua Xu", "Yang Zhao"),
        year = 2022,
        title = "Intraday Return Predictability in the Cryptocurrency Markets: Momentum, Reversal, or Both",
        venue = "North American Journal of Economics and Finance 62, 101733",
        locator = "https://doi.org/10.1016/j.najef.2022.101733",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance =
            "Documents both momentum and reversal in intraday cryptocurrency returns and motivates a " +
                "strict out-of-sample sign test rather than assuming one pattern dominates.",
    )

    val ALBERS_BITCOIN_FRAGMENTATION = Citation(
        key = "albers-cucuringu-howison-shestopaloff-2022",
        authors = listOf("Jakob Albers", "Mihai Cucuringu", "Sam Howison", "Alexander Y. Shestopaloff"),
        year = 2022,
        title = "Fragmentation, Price Formation and Cross-Impact in Bitcoin Markets",
        venue = "Applied Mathematical Finance 28(5), 349–404",
        locator = "https://doi.org/10.1080/1350486X.2022.2080083",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.SUPPORTING,
        relevance = "Finds sub-second cross-venue leader/lagger structure while warning that taker profitability is fee-sensitive.",
    )

    val BRANDVOLD_BITCOIN_PRICE_DISCOVERY = Citation(
        key = "brandvold-molnar-vagstad-valstad-2015",
        authors = listOf("Morten Brandvold", "Peter Molnár", "Kristian Vagstad", "Ole Christian Andreas Valstad"),
        year = 2015,
        title = "Price Discovery on Bitcoin Exchanges",
        venue = "Journal of International Financial Markets, Institutions and Money 36, 18–35",
        locator = "https://doi.org/10.1016/j.intfin.2015.02.010",
        kind = PublicationKind.PEER_REVIEWED_ARTICLE,
        role = EvidenceRole.METHODOLOGY,
        relevance = "Demonstrates that Bitcoin price-discovery leadership is dynamic across exchanges.",
    )
}
