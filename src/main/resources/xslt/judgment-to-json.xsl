<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:lex="urn:lex:content:1"
                xmlns="urn:lex:content:1"
                exclude-result-prefixes="xs lex"
                version="3.0">

    <xsl:output method="xml" encoding="UTF-8" indent="no"/>

    <!--
        Intermediate XML output that mirrors the normalized JSON record.
        SaxonTransformer parses this with Jackson XmlMapper and serializes
        to the canonical JSON shape.
        A secondary <xsl:result-document> emits the plain-text RAG view.
    -->

    <xsl:template match="/">
        <xsl:variable name="root" select="."/>
        <xsl:variable name="judgment" select="$root/*[local-name()='judgment']"/>

        <normalized>
            <contentId><xsl:value-of select="$judgment/*[local-name()='header']/*[local-name()='content_id']"/></contentId>
            <title><xsl:value-of select="$judgment/*[local-name()='header']/*[local-name()='title']"/></title>
            <court><xsl:value-of select="$judgment/*[local-name()='header']/*[local-name()='court']"/></court>
            <jurisdiction><xsl:value-of select="$judgment/*[local-name()='header']/*[local-name()='jurisdiction']"/></jurisdiction>
            <decisionDate><xsl:value-of select="$judgment/*[local-name()='header']/*[local-name()='decision_date']"/></decisionDate>

            <citations>
                <xsl:for-each select="$judgment/*[local-name()='header']/*[local-name()='citations']/*[local-name()='citation']">
                    <citation>
                        <type><xsl:value-of select="@type"/></type>
                        <value><xsl:value-of select="normalize-space(.)"/></value>
                    </citation>
                </xsl:for-each>
            </citations>

            <parties>
                <xsl:for-each select="$judgment/*[local-name()='header']/*[local-name()='parties']/*[local-name()='party']">
                    <party>
                        <role><xsl:value-of select="@role"/></role>
                        <name><xsl:value-of select="normalize-space(.)"/></name>
                    </party>
                </xsl:for-each>
            </parties>

            <paragraphs>
                <xsl:for-each select="$judgment/*[local-name()='body']/*[local-name()='section']">
                    <xsl:variable name="sectionType" select="@type"/>
                    <xsl:for-each select="./*[local-name()='p']">
                        <paragraph>
                            <id><xsl:value-of select="@id"/></id>
                            <section><xsl:value-of select="$sectionType"/></section>
                            <text><xsl:value-of select="normalize-space(.)"/></text>
                        </paragraph>
                    </xsl:for-each>
                </xsl:for-each>
            </paragraphs>
        </normalized>

        <xsl:result-document href="text:full" method="text" encoding="UTF-8">
            <xsl:for-each select="$judgment/*[local-name()='body']//*[local-name()='p']">
                <xsl:value-of select="normalize-space(.)"/>
                <xsl:text>
</xsl:text>
            </xsl:for-each>
        </xsl:result-document>
    </xsl:template>

</xsl:stylesheet>
