package app.fintrack.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.Attractions
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CarRepair
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Chair
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.ChildFriendly
import androidx.compose.material.icons.rounded.Church
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DownhillSkiing
import androidx.compose.material.icons.rounded.DryCleaning
import androidx.compose.material.icons.rounded.Elderly
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.EmojiFoodBeverage
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.Icecream
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Liquor
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalCarWash
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.LocalMall
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.LocalPizza
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.LunchDining
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoneyOff
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Plumbing
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.PriceCheck
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RamenDining
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SmokingRooms
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.SportsTennis
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Subway
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Tram
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.Vaccines
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.fintrack.domain.model.AccountType

/** Группа иконок для выбора при создании категории. */
data class IconGroup(val title: String, val icons: List<Pair<String, ImageVector>>)

/**
 * Единое семейство иконок — Material Symbols Rounded (залитые, со скруглёнными
 * краями). Ключ хранится в базе: существующие ключи не меняются, новые — только добавляются.
 */
object CategoryIcons {
    val groups: List<IconGroup> = listOf(
        IconGroup(
            "Еда и напитки",
            listOf(
                "groceries" to Icons.Rounded.LocalGroceryStore,
                "restaurant" to Icons.Rounded.Restaurant,
                "cafe" to Icons.Rounded.LocalCafe,
                "coffee" to Icons.Rounded.Coffee,
                "fastfood" to Icons.Rounded.Fastfood,
                "pizza" to Icons.Rounded.LocalPizza,
                "bakery" to Icons.Rounded.BakeryDining,
                "lunch" to Icons.Rounded.LunchDining,
                "dinner" to Icons.Rounded.DinnerDining,
                "ramen" to Icons.Rounded.RamenDining,
                "seafood" to Icons.Rounded.SetMeal,
                "icecream" to Icons.Rounded.Icecream,
                "cake" to Icons.Rounded.Cake,
                "tea" to Icons.Rounded.EmojiFoodBeverage,
                "drinks" to Icons.Rounded.LocalDrink,
                "bar" to Icons.Rounded.LocalBar,
                "liquor" to Icons.Rounded.Liquor,
                "kitchen" to Icons.Rounded.Kitchen,
                "delivery" to Icons.Rounded.DeliveryDining,
            ),
        ),
        IconGroup(
            "Транспорт",
            listOf(
                "bus" to Icons.Rounded.DirectionsBus,
                "train" to Icons.Rounded.Train,
                "subway" to Icons.Rounded.Subway,
                "tram" to Icons.Rounded.Tram,
                "taxi" to Icons.Rounded.LocalTaxi,
                "car" to Icons.Rounded.DirectionsCar,
                "electric_car" to Icons.Rounded.ElectricCar,
                "moto" to Icons.Rounded.TwoWheeler,
                "bike" to Icons.Rounded.DirectionsBike,
                "fuel" to Icons.Rounded.LocalGasStation,
                "ev_station" to Icons.Rounded.EvStation,
                "parking" to Icons.Rounded.LocalParking,
                "car_repair" to Icons.Rounded.CarRepair,
                "car_wash" to Icons.Rounded.LocalCarWash,
                "boat" to Icons.Rounded.DirectionsBoat,
                "shipping" to Icons.Rounded.LocalShipping,
            ),
        ),
        IconGroup(
            "Дом и быт",
            listOf(
                "home" to Icons.Rounded.Home,
                "apartment" to Icons.Rounded.Apartment,
                "utilities" to Icons.Rounded.Lightbulb,
                "electricity" to Icons.Rounded.Bolt,
                "water" to Icons.Rounded.WaterDrop,
                "wifi" to Icons.Rounded.Wifi,
                "router" to Icons.Rounded.Router,
                "tv" to Icons.Rounded.Tv,
                "furniture" to Icons.Rounded.Chair,
                "bed" to Icons.Rounded.Bed,
                "sofa" to Icons.Rounded.Weekend,
                "repair" to Icons.Rounded.Build,
                "handyman" to Icons.Rounded.Handyman,
                "construction" to Icons.Rounded.Construction,
                "plumbing" to Icons.Rounded.Plumbing,
                "cleaning" to Icons.Rounded.CleaningServices,
                "laundry" to Icons.Rounded.LocalLaundryService,
                "garden" to Icons.Rounded.Yard,
                "flowers" to Icons.Rounded.LocalFlorist,
                "grass" to Icons.Rounded.Grass,
            ),
        ),
        IconGroup(
            "Здоровье и красота",
            listOf(
                "health" to Icons.Rounded.LocalHospital,
                "medicine" to Icons.Rounded.LocalPharmacy,
                "medical" to Icons.Rounded.MedicalServices,
                "vaccine" to Icons.Rounded.Vaccines,
                "heart" to Icons.Rounded.MonitorHeart,
                "health_insurance" to Icons.Rounded.HealthAndSafety,
                "psychology" to Icons.Rounded.Psychology,
                "beauty" to Icons.Rounded.Spa,
                "haircut" to Icons.Rounded.ContentCut,
                "face" to Icons.Rounded.Face,
                "sport" to Icons.Rounded.FitnessCenter,
                "yoga" to Icons.Rounded.SelfImprovement,
                "smoking" to Icons.Rounded.SmokingRooms,
            ),
        ),
        IconGroup(
            "Покупки",
            listOf(
                "shopping" to Icons.Rounded.ShoppingBag,
                "cart" to Icons.Rounded.ShoppingCart,
                "mall" to Icons.Rounded.LocalMall,
                "store" to Icons.Rounded.Storefront,
                "clothes" to Icons.Rounded.Checkroom,
                "dry_cleaning" to Icons.Rounded.DryCleaning,
                "jewelry" to Icons.Rounded.Diamond,
                "watch" to Icons.Rounded.Watch,
                "electronics" to Icons.Rounded.Devices,
                "laptop" to Icons.Rounded.Laptop,
                "computer" to Icons.Rounded.Computer,
                "phone" to Icons.Rounded.Smartphone,
                "headphones" to Icons.Rounded.Headphones,
                "camera" to Icons.Rounded.PhotoCamera,
                "sell" to Icons.Rounded.LocalOffer,
            ),
        ),
        IconGroup(
            "Отдых и хобби",
            listOf(
                "games" to Icons.Rounded.SportsEsports,
                "movie" to Icons.Rounded.Movie,
                "cinema" to Icons.Rounded.Theaters,
                "theater" to Icons.Rounded.TheaterComedy,
                "music" to Icons.Rounded.MusicNote,
                "subscriptions" to Icons.Rounded.Subscriptions,
                "books" to Icons.Rounded.MenuBook,
                "library" to Icons.Rounded.LibraryBooks,
                "art" to Icons.Rounded.Palette,
                "brush" to Icons.Rounded.Brush,
                "football" to Icons.Rounded.SportsSoccer,
                "basketball" to Icons.Rounded.SportsBasketball,
                "tennis" to Icons.Rounded.SportsTennis,
                "pool" to Icons.Rounded.Pool,
                "hiking" to Icons.Rounded.Hiking,
                "skiing" to Icons.Rounded.DownhillSkiing,
                "park" to Icons.Rounded.Park,
                "attractions" to Icons.Rounded.Attractions,
                "casino" to Icons.Rounded.Casino,
            ),
        ),
        IconGroup(
            "Путешествия",
            listOf(
                "flight" to Icons.Rounded.Flight,
                "hotel" to Icons.Rounded.Hotel,
                "luggage" to Icons.Rounded.Luggage,
                "beach" to Icons.Rounded.BeachAccess,
                "map" to Icons.Rounded.Map,
                "landscape" to Icons.Rounded.Landscape,
                "world" to Icons.Rounded.Public,
                "language" to Icons.Rounded.Language,
            ),
        ),
        IconGroup(
            "Семья и люди",
            listOf(
                "family" to Icons.Rounded.FamilyRestroom,
                "kids" to Icons.Rounded.ChildCare,
                "stroller" to Icons.Rounded.ChildFriendly,
                "toys" to Icons.Rounded.Toys,
                "pets" to Icons.Rounded.Pets,
                "elderly" to Icons.Rounded.Elderly,
                "deal" to Icons.Rounded.People,
                "gift" to Icons.Rounded.CardGiftcard,
                "party" to Icons.Rounded.Celebration,
                "love" to Icons.Rounded.Favorite,
                "charity" to Icons.Rounded.VolunteerActivism,
                "church" to Icons.Rounded.Church,
            ),
        ),
        IconGroup(
            "Учёба и работа",
            listOf(
                "school" to Icons.Rounded.School,
                "book" to Icons.Rounded.Book,
                "stories" to Icons.Rounded.AutoStories,
                "science" to Icons.Rounded.Science,
                "work" to Icons.Rounded.Work,
                "business" to Icons.Rounded.BusinessCenter,
                "badge" to Icons.Rounded.Badge,
                "print" to Icons.Rounded.Print,
                "mail" to Icons.Rounded.Mail,
                "call" to Icons.Rounded.Phone,
                "sim" to Icons.Rounded.SimCard,
                "cloud" to Icons.Rounded.Cloud,
                "code" to Icons.Rounded.Code,
            ),
        ),
        IconGroup(
            "Финансы",
            listOf(
                "salary" to Icons.Rounded.Paid,
                "payments" to Icons.Rounded.Payments,
                "money" to Icons.Rounded.AttachMoney,
                "savings" to Icons.Rounded.Savings,
                "invest" to Icons.Rounded.BarChart,
                "growth" to Icons.Rounded.TrendingUp,
                "decline" to Icons.Rounded.TrendingDown,
                "credit" to Icons.Rounded.CreditCard,
                "bank" to Icons.Rounded.AccountBalance,
                "wallet" to Icons.Rounded.AccountBalanceWallet,
                "receipt" to Icons.Rounded.Receipt,
                "invoice" to Icons.Rounded.RequestQuote,
                "calculate" to Icons.Rounded.Calculate,
                "taxes" to Icons.Rounded.Gavel,
                "insurance" to Icons.Rounded.Shield,
                "security" to Icons.Rounded.Security,
                "money_off" to Icons.Rounded.MoneyOff,
                "price_check" to Icons.Rounded.PriceCheck,
                "refund" to Icons.Rounded.Replay,
                "bonus" to Icons.Rounded.Redeem,
                "euro" to Icons.Rounded.Euro,
                "star" to Icons.Rounded.Star,
            ),
        ),
        IconGroup(
            "Другое",
            listOf(
                "other" to Icons.Rounded.MoreHoriz,
                "flag" to Icons.Rounded.Flag,
                "category" to Icons.Rounded.Category,
            ),
        ),
    )

    val all: List<Pair<String, ImageVector>> = groups.flatMap { it.icons }

    private val byKey = all.toMap()

    fun get(key: String?): ImageVector = key?.let { byKey[it] } ?: Icons.Rounded.Category

    /** Индекс группы, в которой лежит иконка (для начального выбора вкладки). */
    fun groupIndexOf(key: String?): Int = groups.indexOfFirst { g -> g.icons.any { it.first == key } }.coerceAtLeast(0)
}

object AccountIcons {
    fun get(type: AccountType): ImageVector = when (type) {
        AccountType.CASH -> Icons.Rounded.Payments
        AccountType.CARD -> Icons.Rounded.CreditCard
        AccountType.SALARY_CARD -> Icons.Rounded.Work
        AccountType.SAVINGS -> Icons.Rounded.Savings
        AccountType.E_WALLET -> Icons.Rounded.AccountBalanceWallet
        AccountType.CURRENCY -> Icons.Rounded.Euro
        AccountType.CUSTOM -> Icons.Rounded.AccountBalance
        AccountType.PIGGY -> Icons.Rounded.Savings
    }
}

/** Размеры иконок — токены, а не случайные числа. */
object IconSize {
    val sm = 18.dp
    val md = 24.dp
    val lg = 28.dp
}

/** Палитра для счетов и категорий: насыщенные, хорошо читаются в обеих темах. */
val PaletteColors: List<Long> = listOf(
    0xFF2F55E4L, 0xFF6D4AFFL, 0xFF9B51E0L, 0xFFD946EFL, 0xFFEC4899L, 0xFFF43F5EL,
    0xFFEF4444L, 0xFFF97316L, 0xFFF59E0BL, 0xFF10B981L, 0xFF059669L, 0xFF14B8A6L,
    0xFF0EA5E9L, 0xFF475569L, 0xFF94A3B8L,
)
