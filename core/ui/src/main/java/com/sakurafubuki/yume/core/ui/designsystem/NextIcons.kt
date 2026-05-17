package com.sakurafubuki.yume.core.ui.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AppSettingsAlt
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ConnectedTv
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DoubleArrow
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FilterFrames
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.HideSource
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MiscellaneousServices
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PanToolAlt
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Pinch
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ResetTv
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff

object NextIcons {
    val Add = Icons.Rounded.Add
    val Animation = Icons.Rounded.Animation
    val Appearance = Icons.Rounded.Palette
    val ArrowBack = Icons.AutoMirrored.Rounded.ArrowBack
    val ArrowDownward = Icons.Rounded.ArrowDownward
    val ArrowForward = Icons.AutoMirrored.Rounded.ArrowForward
    val ArrowUpward = Icons.Rounded.ArrowUpward
    val AspectRatio = Icons.Rounded.AspectRatio
    val Audio = Icons.Rounded.Audiotrack
    val AutoFix = Icons.Rounded.AutoFixHigh
    val AutoGraph = Icons.Rounded.AutoGraph
    val Background = Icons.Rounded.FlipToBack
    val Bold = Icons.Rounded.FormatBold
    val Bookmark = Icons.Rounded.Bookmark
    val Brightness = Icons.Rounded.BrightnessHigh
    val BugReport = Icons.Rounded.BugReport
    val ButtonsPosition = Icons.Rounded.AppSettingsAlt
    val Calendar = Icons.Rounded.CalendarMonth
    val Caption = Icons.Rounded.ClosedCaption
    val Check = Icons.Rounded.Check
    val CheckBox = Icons.Rounded.CheckCircle
    val CheckBoxOutline = Icons.Rounded.RadioButtonUnchecked
    val Close = Icons.Rounded.Close
    val Cloud = Icons.Rounded.Cloud
    val CloudOff = Icons.Rounded.CloudOff
    val Code = Icons.Rounded.Code
    val Colorize = Icons.Rounded.Colorize
    val ColorPalette = Icons.Rounded.Palette
    val Contrast = Icons.Rounded.Contrast
    val Copy = Icons.Rounded.ContentCopy
    val DarkMode = Icons.Rounded.DarkMode
    val DashBoard = Icons.Rounded.Dashboard
    val Decoder = Icons.Rounded.DeveloperBoard
    val Delete = Icons.Rounded.Delete
    val DeleteSweep = Icons.Rounded.DeleteSweep
    val DeselectAll = Icons.Rounded.Deselect
    val DoubleTap = Icons.Rounded.DoubleArrow
    val Download = Icons.Rounded.Download
    val Edit = Icons.Rounded.Edit
    val Error = Icons.Rounded.Error
    val Extension = Icons.Rounded.Extension
    val ExtraSettings = Icons.Rounded.MiscellaneousServices
    val Fast = Icons.Rounded.FastForward
    val FastForward = Icons.Rounded.FastForward
    val FileOpen = Icons.Rounded.FileOpen
    val Focus = Icons.Rounded.CenterFocusStrong
    val Folder = Icons.Rounded.Folder
    val FolderOff = Icons.Rounded.FolderOff
    val Font = Icons.Rounded.FontDownload
    val FontSize = Icons.Rounded.FormatSize
    val Frame = Icons.Rounded.FilterFrames
    val Fullscreen = Icons.Rounded.Fullscreen
    val FullscreenExit = Icons.Rounded.FullscreenExit
    val Gradient = Icons.Rounded.Gradient
    val GraphicEq = Icons.Rounded.GraphicEq
    val HdrAuto = Icons.Rounded.HdrAuto
    val Headset = Icons.Rounded.Headset
    val HeadsetOff = Icons.Rounded.HeadsetOff
    val Help = Icons.AutoMirrored.Rounded.HelpOutline
    val HideSource = Icons.Rounded.HideSource
    val HighQuality = Icons.Rounded.HighQuality
    val History = Icons.Rounded.History
    val Home = Icons.Rounded.Home
    val Image = Icons.Rounded.Image
    val Info = Icons.Rounded.Info
    val Label = Icons.Rounded.Label
    val Language = Icons.Rounded.Translate
    val Length = Icons.Rounded.Straighten
    val Link = Icons.Rounded.Link
    val List = Icons.AutoMirrored.Rounded.List
    val Location = Icons.Rounded.LocationOn
    val Lock = Icons.Rounded.Lock
    val LockOpen = Icons.Rounded.LockOpen
    val Loop = Icons.Rounded.Loop
    val Logout = Icons.AutoMirrored.Rounded.Logout
    val Lyrics = Icons.Rounded.Lyrics
    val Menu = Icons.Rounded.Menu
    val MoreVert = Icons.Rounded.MoreVert
    val Movie = Icons.Rounded.LocalMovies
    val Notifications = Icons.Rounded.Notifications
    val Pan = Icons.Rounded.PanToolAlt
    val PhotoFilter = Icons.Rounded.PhotoFilter
    val Pinch = Icons.Rounded.Pinch
    val Pip = Icons.Rounded.PictureInPictureAlt
    val Play = Icons.Rounded.PlayArrow
    val Player = Icons.Rounded.PlayCircle
    val PlaylistAdd = Icons.AutoMirrored.Rounded.PlaylistAdd
    val Priority = Icons.Rounded.PriorityHigh
    val Refresh = Icons.Rounded.Refresh
    val Repeat = Icons.Rounded.Repeat
    val RepeatOne = Icons.Rounded.RepeatOne
    val Replay = Icons.Rounded.Replay10
    val Resume = Icons.Rounded.ResetTv
    val Rotation = Icons.Rounded.ScreenRotationAlt
    val Search = Icons.Rounded.Search
    val SelectAll = Icons.Rounded.SelectAll
    val Selection = Icons.Rounded.DoneAll
    val Send = Icons.AutoMirrored.Rounded.Send
    val Sensitivity = Icons.Rounded.Tune
    val Settings = Icons.Rounded.Settings
    val Share = Icons.Rounded.Share
    val Shuffle = Icons.Rounded.Shuffle
    val Size = Icons.AutoMirrored.Rounded.CompareArrows
    val SkipNext = Icons.Rounded.SkipNext
    val SkipPrevious = Icons.Rounded.SkipPrevious
    val SmartButton = Icons.Rounded.SmartButton
    val Sort = Icons.AutoMirrored.Rounded.Sort
    val SpeakerNotes = Icons.AutoMirrored.Rounded.SpeakerNotes
    val Speed = Icons.Rounded.Speed
    val Star = Icons.Rounded.Star
    val Style = Icons.Rounded.Style
    val Subtitle = Icons.Rounded.Subtitles
    val SwipeHorizontal = Icons.Rounded.Swipe
    val SwipeVertical = Icons.Rounded.SwipeVertical
    val Sync = Icons.Rounded.Sync
    val Tag = Icons.Rounded.Tag
    val Tap = Icons.Rounded.TouchApp
    val TextSnippet = Icons.AutoMirrored.Rounded.TextSnippet
    val Timer = Icons.Rounded.Timer
    val Title = Icons.Rounded.Title
    val Tv = Icons.Rounded.ConnectedTv
    val Update = Icons.Rounded.Update
    val Video = Icons.Rounded.Movie
    val Visibility = Icons.Rounded.Visibility
    val VisibilityOff = Icons.Rounded.VisibilityOff
    val VolumeUp = Icons.AutoMirrored.Rounded.VolumeUp
    val Warning = Icons.Rounded.Warning
    val Wifi = Icons.Rounded.Wifi
    val WifiOff = Icons.Rounded.WifiOff
}
