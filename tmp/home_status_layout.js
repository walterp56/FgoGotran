const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/HomeScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
const cardStart = text.indexOf('@Composable\nprivate fun StatusActionCard(');
const cardEnd = text.indexOf('@Composable\nprivate fun LanguageDialogOption(');
if (cardStart < 0 || cardEnd < 0 || cardEnd <= cardStart) throw new Error('status card bounds not found');
const cardBlock = `@Composable
private fun StatusActionCard(
    label: String,
    statusText: String,
    statusColor: Color,
    enabled: Boolean,
    actionText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(enabled)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(actionText, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (enabled) Color(0xFF4CAF50) else Color(0xFFFF9800),
        modifier = Modifier.size(10.dp)
    ) {}
}

`;
text = text.slice(0, cardStart) + cardBlock + text.slice(cardEnd);
const rowStart = text.indexOf('/**\n * A row showing a permission/service status with a colored dot indicator.\n */\n@Composable\nprivate fun StatusRow(');
const rowEnd = text.indexOf('/** Both start paths must check the live binding immediately before launching the runner. */');
if (rowStart < 0 || rowEnd < 0 || rowEnd <= rowStart) throw new Error('status row bounds not found');
text = text.slice(0, rowStart) + text.slice(rowEnd);
fs.writeFileSync(filePath, text, 'utf8');
console.log('home status layout updated');
